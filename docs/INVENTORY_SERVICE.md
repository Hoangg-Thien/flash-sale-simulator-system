# Dịch vụ tồn kho

`services/inventory-service` chạy độc lập bằng Java 21 và Spring Boot. Dịch vụ quản lý cơ sở dữ liệu PostgreSQL `inventory_db`, gồm tồn kho khả dụng, trạng thái giữ hàng và lịch sử di trú Flyway riêng. Bên gọi cung cấp mã đơn hàng và mã sản phẩm. Ứng dụng trong `services/order-service` quản lý đơn hàng, dòng hàng và danh mục sản phẩm. Luồng đặt hàng gọi API giữ hàng qua HTTP. Dịch vụ tồn kho khởi động với dữ liệu trống. Thẻ Git `monolith-v1` là mốc đối chiếu cho các phép đo.

## API nội bộ

Các đường dẫn đều bắt đầu bằng `/internal/v1`. Docker Compose dùng mạng dịch vụ cho REST nội bộ giữa Order và Inventory, cùng các mạng DB riêng cho PostgreSQL và Redis. Các cổng Inventory, PostgreSQL và Redis không được công khai trên máy chủ. Thao tác đặt lại tồn kho dành cho quản trị và mô phỏng.

| Phương thức và đường dẫn | Dữ liệu gửi | Kết quả |
| --- | --- | --- |
| `POST /internal/v1/reservations` | `{"orderId":1001,"productId":42,"quantity":3}` | `200` kèm trạng thái hiện tại. Ở lần đầu nhận mã đơn hàng, dịch vụ ghi nhận kết quả giữ hàng hoặc từ chối. |
| `GET /internal/v1/reservations/{orderId}` | | `200` kèm trạng thái hiện tại hoặc `404` nếu chưa có bản ghi cho mã đơn hàng. |
| `POST /internal/v1/reservations/{orderId}/release` | `{"productId":42,"quantity":3}` | `200` kèm trạng thái sau khi hoàn kho hoặc hủy giữ hàng. Dịch vụ tạo bản ghi hủy nếu chưa có mã đơn hàng và giữ nguyên trạng thái của đơn đã bị từ chối. |
| `POST /internal/v1/inventory/{productId}/initialize` | `{"stock":10}` | `200` kèm tồn hiện tại. Tạo tồn kho một lần. Gọi lại với cùng số lượng khởi tạo vẫn giữ nguyên số tồn khả dụng sau mua hàng. Trả `409` khi số lượng khởi tạo khác hoặc dòng kho cũ chưa có thông tin khởi tạo. |
| `GET /internal/v1/inventory/{productId}` | | `200` kèm `{productId,stock,version}` hoặc `404` nếu chưa có bản ghi tồn kho. |
| `PUT /internal/v1/inventory/{productId}` | `{"stock":10}` | `200` kèm `{productId,stock,version}`. Tạo mới hoặc đặt lại số lượng khả dụng. Các lượt giữ hàng hiện tại vẫn giữ trạng thái và số lượng có thể hoàn. |

Phản hồi giữ hàng gồm `orderId`, `productId`, `quantity` và `status`.

- `RESERVED` nghĩa là giao dịch trừ kho đã hoàn tất.
- `REJECTED` là kết quả cuối cùng khi thiếu hàng, chưa có bản ghi tồn kho hoặc có xung đột phiên bản trong chế độ kiểm tra phiên bản. Gửi lại yêu cầu vẫn nhận kết quả này sau khi đặt lại tồn kho.
- `RELEASED` là kết quả cuối cùng khi đã hoàn kho hoặc khi yêu cầu hủy đến trước lần trừ kho.
- Hai thao tác ghi nhận và hủy giữ hàng trả về `409` nếu mã sản phẩm hoặc số lượng khác yêu cầu đầu tiên của cùng mã đơn hàng. Đặt lại tồn kho cũng trả về `409` nếu việc hoàn những lượt giữ hàng đang có sẽ làm tràn cột `stock`.
- Mã đơn hàng và mã sản phẩm là số nguyên dương 64 bit. Số lượng giữ hàng là số nguyên dương 32 bit. Tồn kho là số nguyên không âm 32 bit. Trường bắt buộc bị thiếu hoặc không hợp lệ dẫn đến phản hồi `400`.
- Khi không lấy được khóa trong chế độ `REDIS`, dịch vụ trả về `503`. Hãy gửi lại cùng mã đơn hàng và dữ liệu. Nếu Redis xử lý lệnh lấy khóa sau khi bên gọi đã hết thời gian chờ, sản phẩm có thể bị khóa tạm thời đến khi khóa hết hạn. Nếu mất phản hồi HTTP, có thể gửi lại yêu cầu hoặc tra cứu trạng thái đã lưu.

## Ranh giới giao dịch

Giữ hàng và hoàn kho đều chạy trong một giao dịch PostgreSQL với mức cô lập `READ COMMITTED`. Mỗi thao tác bắt đầu bằng `INSERT ... ON CONFLICT (order_id) DO NOTHING` trên cột mã đơn hàng có ràng buộc duy nhất. Yêu cầu trùng chờ giao dịch đang chèn bản ghi hoàn tất, rồi đọc bản ghi đã lưu để đối chiếu dữ liệu. Chỉ giao dịch chèn được bản ghi mới thử trừ kho. Chế độ mặc định dùng `UPDATE ... WHERE stock >= quantity` để PostgreSQL kiểm tra tồn kho khi giữ khóa dòng. Yêu cầu trùng khớp trả về trạng thái đã lưu và giữ nguyên tồn kho.

Khi hoàn kho, dịch vụ khóa bản ghi giữ hàng bằng `SELECT ... FOR UPDATE`. Chỉ bản ghi ở trạng thái `RESERVED` được cộng lại tồn kho và chuyển sang `RELEASED`. Nếu yêu cầu hủy chèn bản ghi trước, bản ghi hủy ngăn mọi lần trừ kho sau đó cho mã đơn hàng này. Cập nhật tồn kho và trạng thái giữ hàng được xác nhận hoặc hoàn tác cùng nhau. Thao tác đặt lại tồn kho cũng khóa dòng tồn kho trước khi kiểm tra số lượng cần chừa để có thể hoàn hàng.

Bảng `inventory` có `product_id` duy nhất và ràng buộc tồn kho không âm. Bên gọi truyền mã sản phẩm và mã đơn hàng qua API. Bảng `stock_reservations` lưu được kết quả từ chối và bản ghi hủy trước khi tồn tại dòng tồn kho, nên cột `product_id` của bảng này không đặt khóa ngoại đến `inventory`. Trạng thái `RESERVED` chỉ được lưu khi trừ kho thành công trong cùng giao dịch. API quản trị tồn kho hỗ trợ tạo mới và đặt lại số lượng. [Mức cô lập giao dịch PostgreSQL](https://www.postgresql.org/docs/17/transaction-iso.html) · [Khóa cấp dòng PostgreSQL](https://www.postgresql.org/docs/17/explicit-locking.html)

### Chế độ khóa

Đặt `INVENTORY_LOCK_MODE` giống nhau cho các phiên bản đang chạy. Trong mọi chế độ an toàn, PostgreSQL bảo đảm mỗi mã đơn hàng có tối đa một bản ghi giữ hàng và điều phối các thay đổi trạng thái, tồn kho.

| Chế độ | Cách trừ kho |
| --- | --- |
| `ATOMIC` (mặc định) | Dùng một câu lệnh cập nhật có điều kiện. Chế độ này chỉ cần PostgreSQL. |
| `PESSIMISTIC` | Khóa dòng tồn kho trong PostgreSQL, sau đó đọc, kiểm tra và cập nhật. |
| `OPTIMISTIC` | Đọc, kiểm tra và cập nhật theo phiên bản đã đọc. Xung đột có thể dẫn đến từ chối dù vẫn còn hàng. |
| `REDIS` | Redisson giữ khóa theo sản phẩm từ trước khi mở giao dịch đến sau khi xác nhận hoặc hoàn tác. Câu lệnh cập nhật vẫn kiểm tra phiên bản trong cơ sở dữ liệu. |
| `NONE` | Cố ý cho phép ghi đè làm mất cập nhật để mô phỏng. Chế độ này yêu cầu `INVENTORY_LOCK_MODE=NONE` và `INVENTORY_UNSAFE_MODES_ENABLED=true`. Cấu hình Compose độc lập đặt cờ này thành `false`. |

## Chạy kiểm thử

Chạy tại thư mục gốc của dự án với Java 21, Maven và Docker.

```bash
mvn clean package
mvn -pl services/inventory-service test
for mode in PESSIMISTIC OPTIMISTIC REDIS; do
  mvn -pl services/inventory-service -Dinventory.test.lock-mode="$mode" test || break
done
```

Testcontainers khởi động hai tiến trình Java độc lập trên các cổng ngẫu nhiên. Bộ kiểm thử gửi đồng thời 24 yêu cầu giữ hàng giống nhau và 24 yêu cầu hoàn kho giống nhau, kiểm tra dữ liệu xung đột và đối chiếu tồn kho dưới tải từ 32 đơn hàng khác nhau. Các ca còn lại kiểm tra yêu cầu hủy đến trước hoặc đồng thời với yêu cầu giữ hàng, thiếu hàng, đường dẫn thiếu tiền tố phiên bản và gián đoạn Redis. Một trigger PostgreSQL chạy lúc xác nhận giao dịch chủ động gây lỗi sau các câu lệnh ghi. Các câu truy vấn SQL kiểm tra cả tồn kho và trạng thái giữ hàng đều được hoàn tác. Báo cáo nằm trong `services/inventory-service/target/surefire-reports/`.

## Chạy độc lập bằng Docker Compose

```bash
docker compose -p inventory-local -f docker-compose.inventory.yml up --build -d --wait
docker compose -p inventory-local -f docker-compose.inventory.yml ps
```

Hai phiên bản dịch vụ dùng chung `inventory_db` và có thể dùng chung Redis. Tệp `docker-compose.yml` ở thư mục gốc chạy cả dịch vụ đặt hàng và dịch vụ tồn kho. Để chạy dịch vụ tồn kho ở chế độ `REDIS`, đặt `INVENTORY_LOCK_MODE=REDIS` trước lệnh Compose.

Tệp JAR chạy độc lập nằm ở `services/inventory-service/target/inventory-service-0.0.1-SNAPSHOT.jar`. Khi chạy trực tiếp, cấu hình `INVENTORY_DB_URL`, `INVENTORY_DB_USER` và `INVENTORY_DB_PASSWORD` để kết nối PostgreSQL. Chế độ `REDIS` cũng hỗ trợ cấu hình `INVENTORY_REDIS_ADDRESS`.

Ví dụ dưới đây dùng một container HTTP tạm thời trên mạng Compose. Tồn kho ban đầu là 10. Yêu cầu giữ 3 đơn vị được gửi đến phiên bản thứ nhất và đọc lại từ phiên bản thứ hai. Sau khi hoàn kho, tồn kho trở về 10.

```bash
inventory_http() {
  docker run --rm --network inventory-local_default curlimages/curl:8.14.1 -fsS "$@"
}

id=$(date +%s)
a=http://inventory-a:8081/internal/v1
b=http://inventory-b:8081/internal/v1
inventory_http -X PUT -H 'Content-Type: application/json' \
  -d '{"stock":10}' "$a/inventory/$id"
inventory_http -H 'Content-Type: application/json' \
  -d "{\"orderId\":$id,\"productId\":$id,\"quantity\":3}" "$a/reservations"
inventory_http "$b/reservations/$id"
inventory_http -H 'Content-Type: application/json' \
  -d "{\"productId\":$id,\"quantity\":3}" "$b/reservations/$id/release"
inventory_http "$a/inventory/$id"

docker compose -p inventory-local -f docker-compose.inventory.yml down
```

## Giới hạn vận hành

Dịch vụ giữ bản ghi giữ hàng và bản ghi hủy vô thời hạn để xử lý yêu cầu gửi lại. Order Service đối soát các đơn đang chờ qua API giữ hàng. Đặt lại tồn kho dành cho mô phỏng vì thao tác này thay đổi số lượng khả dụng độc lập với giao dịch mua hàng. Bộ kiểm thử tích hợp đã kiểm tra tranh chấp đồng thời và hoàn tác giao dịch. Xem cách đối soát khi mất phản hồi và khởi động lại Order trong [Vòng đời đơn hàng](VONG_DOI_DON_HANG.md).
