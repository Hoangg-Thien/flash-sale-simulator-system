# Chuyển dữ liệu tồn kho

Kho khả dụng thuộc `inventory_db`. Danh mục sản phẩm, giá và đơn hàng thuộc `order_db`. Khi nâng cấp một bản sao cơ sở dữ liệu đơn khối, Flyway V9 sao chép số lượng khả dụng cuối cùng từ `inventory.stock` sang lệnh khởi tạo `products.pending_initial_stock` rồi xóa bảng kho cũ trong cùng giao dịch. Giá trị này là lệnh dùng một lần. Order chỉ mở bán sản phẩm sau khi Inventory xác nhận đã khởi tạo và Order đã xóa lệnh.

## Chuyển từ ứng dụng đơn khối

1. Dừng lưu lượng mua, tạo sản phẩm, mô phỏng và mọi tiến trình có thể ghi vào cơ sở dữ liệu nguồn. Chờ toàn bộ giao dịch đang chạy kết thúc. Giữ nguyên các volume nguồn và lưu bản sao bằng `pg_dump`. Bản sao phải chứa dữ liệu, bảng lịch sử Flyway và các bộ sinh mã đơn hàng/sản phẩm.
2. Dùng một dự án Compose mới với hai cơ sở dữ liệu đích trống. Khởi động riêng hai máy chủ PostgreSQL. Khôi phục bản sao vào `order_db` trước khi chạy Order Service. Dùng `inventory_db` mới, kể cả khi trước đó đã chạy thử dịch vụ kho độc lập. Bản ghi hủy theo mã đơn hàng và kho mẫu từ lần chạy thử có thể trùng với mã dữ liệu thật.
3. Khởi động hai dịch vụ. Order gửi `POST /internal/v1/inventory/{productId}/initialize` với `{"stock":N}`. Inventory chèn kho và số lượng khởi tạo gốc trong một giao dịch. Gửi lại đúng lệnh sẽ trả về kho hiện tại, kể cả khi đã bán bớt. Nếu số lượng gốc khác, hoặc dòng kho chỉ được tạo bằng API quản trị, Inventory trả về `409`. Order tiếp tục chặn mua sản phẩm này và ghi mã sản phẩm vào nhật ký.
4. Đợi số lệnh khởi tạo còn lại về 0 rồi đối chiếu số lượng khả dụng từng sản phẩm giữa bản sao nguồn và Inventory. Chỉ chuyển lưu lượng sau khi hai danh sách trùng khớp. Sau khi mở bán, sử dụng cặp cơ sở dữ liệu mới cùng nhau. Quay lại ảnh chụp cũ của riêng một dịch vụ có thể làm mất quan hệ giữa đơn hàng và lượt giữ hàng.

Ví dụ dưới đây giả định đã có bản sao nhất quán `flashsale.dump` định dạng custom của `pg_dump -Fc`. Tên dự án `flashsale-cutover` phải mới để Compose cấp volume trống. Giữ các lệnh và tệp sao lưu trong môi trường có quyền truy cập dữ liệu thật.

```bash
docker compose -p flashsale-cutover up -d --wait order-postgres inventory-postgres
docker compose -p flashsale-cutover exec -T order-postgres sh -c \
  'pg_restore --exit-on-error --single-transaction --no-owner --no-privileges -U "$POSTGRES_USER" -d order_db' < flashsale.dump
docker compose -p flashsale-cutover up --build -d --wait
docker compose -p flashsale-cutover exec -T order-postgres sh -c \
  'psql -U "$POSTGRES_USER" -d order_db -c "SELECT count(*) FROM products WHERE pending_initial_stock IS NOT NULL"'
docker compose -p flashsale-cutover exec -T inventory-postgres sh -c \
  'psql -U "$POSTGRES_USER" -d inventory_db -Atc "SELECT product_id,stock FROM inventory ORDER BY product_id"' > stock-after.txt
```

Trước khi chuyển, chạy cùng truy vấn `SELECT product_id,stock FROM inventory ORDER BY product_id` bằng `psql -At` trên dữ liệu nguồn đã đóng băng, lưu vào `stock-before.txt`. Kiểm tra bằng `diff -u stock-before.txt stock-after.txt`. Nếu Flyway phát hiện sản phẩm thiếu dòng kho nguồn, V9 dừng và hoàn tác. Khôi phục dữ liệu nguồn đúng rồi chạy lại.

## Tạo sản phẩm và phục hồi

`POST /api/v1/products` ghi danh mục và lệnh khởi tạo trước khi gọi Inventory. Khi Inventory xác nhận, Order trả về `201` cùng số lượng hiện có. Nếu xác nhận chưa về, Order trả về `202` cùng mã sản phẩm và `stock: null`. Dùng `GET /api/v1/products/{id}` để kiểm tra lại sản phẩm đó. Mỗi lần gửi POST tạo sản phẩm mới có thể cấp một mã khác.

Nếu Order dừng sau khi Inventory đã khởi tạo kho, tác vụ định kỳ hoặc lần đọc sản phẩm kế tiếp gửi lại lệnh gốc. Cơ sở dữ liệu Inventory lưu số lượng khởi tạo gốc riêng với số lượng khả dụng. Nhờ đó việc gửi lại lệnh không bổ sung lượng hàng đã bán. Kho đang chờ xác nhận trả về `503` khi tạo đơn hàng.

## Mô phỏng

Bật cấu hình `lab` trên Order bằng `ORDER_PROFILES=docker,lab docker compose up -d`. Tạo sản phẩm riêng cho mỗi lượt thử. Yêu cầu mô phỏng dùng chế độ khóa đang triển khai ở Inventory, đặt lại kho qua API quản trị rồi gọi luồng mua hàng thông thường với khóa idempotency riêng cho mỗi yêu cầu. Kết quả ghi nhận trạng thái tại thời điểm trả lời. `pendingCount` đếm các đơn còn chờ, `successCount` đếm đơn đã xác nhận và `failedCount` đếm đơn bị từ chối hoặc lỗi thực thi. Dùng mã đơn hàng trong kết quả để tra cứu trạng thái sau đối soát. Chỉ chạy đặt lại kho trên sản phẩm thử nghiệm khi các đơn trước đó đã xử lý xong.

## Kết quả chạy thử chuyển dữ liệu

Ngày 24/11/2026, phép thử dùng hai dự án Compose riêng và một sản phẩm có 12 đơn vị. Ứng dụng đơn khối bán 3 đơn vị trước khi dừng. Sau khi khôi phục bản sao bằng `pg_restore` và chạy Flyway, số lượng khả dụng trong Inventory là 9, hàng đợi khởi tạo trống và bảng `inventory` cũ đã được xóa khỏi Order. Gửi lại khóa của đơn cũ trả về HTTP 200, số bản ghi giữ hàng vẫn là 0. Đơn mới mang mã 2 mua 2 đơn vị, có bản ghi `RESERVED` và số tồn cuối cùng là 7. Đây là kết quả với dữ liệu thử nghiệm.
