# Kiểm chứng tự động hai instance dùng chung PostgreSQL/Redis

Phạm vi: Giai đoạn 2 của kế hoạch chuyển đổi phiên bản 2, nhánh `feature/hardening`, SHA bắt đầu `3c1dc15`. Không tách dịch vụ hoặc thêm Gateway/Kafka. Báo cáo ghi lại trạng thái trước khi commit nội dung Giai đoạn 2; việc commit local về sau được thực hiện theo yêu cầu riêng của người dùng. Tag `monolith-v1` được để lại cho người dùng sau khi commit nội dung đã kiểm chứng; gắn tag trước khi commit sẽ trỏ nhầm vào commit Giai đoạn 1.

## Thay đổi chính

| Tệp | Mục đích |
|---|---|
| `docker-compose.yml` | Hai app rõ ràng `app-a`/`app-b` ở 8080/8081, chung PostgreSQL/Redis; dùng chung cấu hình qua YAML anchor và giữ hành vi không tự khởi động lại có sẵn |
| `load-test/multi-instance.compose.yml` | Project đo dùng cổng 18080/18081, không mở cổng DB/Redis và đặt giới hạn tài nguyên |
| `config/RequestCorrelationFilter.java` | Nhận/tạo requestId an toàn, thêm header nhận diện, ghi log HTTP và khôi phục MDC khi xong |
| `exception/ErrorResponse.java` | Thêm requestId vào phản hồi lỗi, giữ nguyên các trường và mã HTTP hiện có |
| `application.yaml` | Cấu hình instanceId từ môi trường, mẫu log ưu tiên MDC và có giá trị dự phòng lúc khởi động |
| `service/SimulationService.java` | Sao chép MDC sang worker mô phỏng rồi dọn ở `finally`, không đổi thuật toán giao dịch/mua hàng |
| `RequestCorrelationIntegrationTest.java` | Header, nội dung lỗi, log, tạo ID, thay ID không hợp lệ, dọn/khôi phục MDC và truyền context sang worker |
| `MultiInstanceIntegrationTest.java` | Hai JVM độc lập, cổng ngẫu nhiên, Testcontainers DB/Redis chung, mua đồng thời và đối chiếu dữ liệu đã lưu |
| `load-test/multi-instance.js` | Kịch bản k6 luân phiên hai URL, nhận diện traffic hai node, kiểm tra requestId và stock cuối |
| `run-multi-instance.ps1`, `helpers/verify-multi-instance.ps1` | Tự dựng/chạy/dọn, truy vấn DB, đối chiếu HTTP/log/số đơn/tổng số lượng/stock sau mỗi lượt |
| `test-multi-instance-verification.ps1` | Kiểm tra âm để verifier không chấp nhận dữ liệu sai hoặc thiếu, kèm trường hợp mua nhiều đơn vị và số người mua ít hơn stock |
| `.github/workflows/load-test.yml` | Thêm lựa chọn thủ công `multi-instance`; bỏ bước khởi động stack mặc định khi runner riêng quản lý tài nguyên; không chạy k6 trong pull request |
| `.gitattributes` | Giữ nguyên byte các tệp đo và log đã ghi, kể cả kết quả bổ sung trong thư mục con |
| `multi-instance.md`, `README.md` | Thay hướng dẫn thủ công bằng lệnh kiểm chứng tự động và mô tả header/giới hạn |

Root Compose bỏ tên container cố định để các project không đụng nhau. Cổng và volume mặc định của PostgreSQL/Redis được giữ nguyên ở cấu hình root; tệp ghi đè chỉ áp dụng cho phép đo riêng. Các tệp kế hoạch chưa tracked có sẵn được giữ nguyên. Không sửa các chiến lược khóa, OrderService, repository tồn kho hoặc migration V1–V7.

## Kiểm thử và lỗi đã xử lý

1. **RED trước implementation:** 6 case requestId/MDC thất bại đúng phần thiếu; 17 case hai JVM đã khởi động tiến trình thật nhưng thất bại vì chưa có header nhận diện. Xem `correlation-red.txt`, `two-jvm-red.txt`; nhật ký đầy đủ trên máy là `correlation-red.log`, `multi-instance-red.log`.
2. **GREEN theo nhóm:** 23/23 case mới đạt sau khi thêm bộ lọc và truyền MDC.
3. **Lỗi ở lượt toàn bộ đầu tiên:** một case phát hiện header đúng instance nhưng log giữ `local` từ Spring context đầu tiên. Đã sửa mẫu log để lấy instanceId từ MDC trước, chỉ dùng cấu hình làm giá trị dự phòng. Không làm yếu assertion.
4. **`clean package` cuối:** **107 trường hợp, 106 đạt, 1 bỏ qua có sẵn, 0 failure/error; BUILD SUCCESS**, khoảng 1 phút 50 giây. Xem [suite.txt](suite.txt). `FlashSaleApplicationTests.contextLoads` vẫn có `@Disabled` từ trước; không thêm trường hợp bỏ qua.
5. **Hai JVM:** 16 case an toàn gồm bốn mode × stock 0/1/10 và mua 3 đơn vị khi stock 10. Mỗi case có 20 người mua bắt đầu qua CountDownLatch, chia 10 request/JVM. So sánh trực tiếp số đơn, số khóa riêng biệt và tổng số lượng trong DB; tồn kho không âm và bằng số đầu trừ tổng số lượng đã mua. Hai API cùng đọc đúng stock đã lưu. ATOMIC/PESSIMISTIC/REDIS còn phải đạt số đơn tối đa stock cho phép; OPTIMISTIC được phép từ chối do xung đột. NONE có case riêng với tối đa năm sản phẩm thử độc lập để quan sát mất cập nhật.
6. **Verifier:** kiểm tra âm từ chối năm loại dữ liệu sai và trường số trống; trường hợp trống đã được chứng minh RED trước khi thêm guard, tránh PowerShell ép chuỗi trống thành số 0.
7. **Review độc lập:** không phát hiện lỗi Critical. Có một lỗi Important ở tham số ít người mua: verifier ban đầu yêu cầu bán hết stock dù chỉ gửi 2 request. Đã thêm case RED, sửa kỳ vọng thành `min(attempts, floor(initialStock / quantity))`, kiểm tra GREEN và chạy bổ sung hai container với cấu hình đó. Các bản ghi DB của lượt mặc định cũng được kiểm tra lại bằng verifier cuối.

Các regression Giai đoạn 0–1 vẫn chạy trong toàn bộ suite, gồm thứ tự Redis commit/nhả khóa, rollback, Redis outage, ATOMIC/version/timestamp, stale JPA context và nâng cấp V6 → V7. Chỉ thay đổi PS verifier sau lượt `clean package` cuối; mã Java/YAML ứng dụng đã kiểm thử được giữ nguyên.

## Kết quả k6 với 20 người mua

Một lượt làm nóng mỗi mode rồi ba lượt đo. Tất cả **20 lần gọi k6** (5 làm nóng + 15 đo) trả mã thoát 0; mỗi lượt có **10 request mua thực nhận ở app-a và 10 ở app-b**, kiểm tra bằng header và số dòng log HTTP tương ứng. Mọi phép kiểm tra k6 đều đạt, không có lỗi HTTP ngoài các phản hồi 409 dự kiến.

| Mode | Stock đầu mỗi lượt | Đơn thành công lượt 1/2/3 | Stock cuối lượt 1/2/3 | Bán vượt stock lượt 1/2/3 | Khoảng p95 HTTP (ms) |
|---|---:|---|---|---|---:|
| ATOMIC | 10 | 10 / 10 / 10 | 0 / 0 / 0 | 0 / 0 / 0 | 143.72–224.88 |
| OPTIMISTIC | 10 | 2 / 2 / 2 | 8 / 8 / 8 | 0 / 0 / 0 | 66.93–134.96 |
| PESSIMISTIC | 10 | 10 / 10 / 10 | 0 / 0 / 0 | 0 / 0 / 0 | 98.83–126.06 |
| REDIS | 10 | 10 / 10 / 10 | 0 / 0 / 0 | 0 / 0 / 0 | 202.52–318.49 |
| NONE, riêng biệt | 1 | 20 / 20 / 19 | 0 / 0 / 0 | 19 / 19 / 18 | 158.08–207.38 |

Số đơn ở bảng được đối chiếu với PostgreSQL, không chỉ lấy từ HTTP. Trong cả 12 lượt đo an toàn, `purchased_units <= initial_stock`, `final_stock >= 0` và `final_stock = initial_stock - purchased_units`; số đơn và khóa riêng biệt khớp số phản hồi 201. Khi quantity khác 1, verifier dùng tổng số lượng từ `order_items`, không lấy số đơn thay cho số lượng hàng.

OPTIMISTIC còn 8 đơn vị vì các xung đột bị từ chối mà không thử lại; đây là giới hạn có sẵn, không phải bảo đảm bán hết hàng. NONE cho thấy stock không âm vẫn có thể bán vượt: ba sản phẩm riêng, tổng stock đầu 3 nhưng có 59 đơn thành công. Nó được loại khỏi các assertion dành cho mode an toàn, đồng thời runner bắt buộc quan sát ít nhất một lượt đo bán vượt mới coi phần trình diễn là đạt.

Các phân vị/RPS bao gồm HTTP chuẩn bị và đọc stock cuối, không phải chỉ độ trễ mua hàng. Không dùng các lượt ngắn này để xếp hạng công suất xử lý đơn thành công; cấu hình NONE cũng có stock khác nhóm an toàn. Ghi chú review về việc bổ sung riêng metric POST được để lại cho lần đo hiệu năng chuyên sâu.

## Kiểm tra bổ sung ít người mua

Chạy `-Rounds 1 -Users 2` vào thư mục [low-demand](low-demand/), không bật NONE. Cả 8 lần gọi k6 (4 làm nóng + 4 đo) đạt; mỗi instance nhận 1 request/lượt. ATOMIC/PESSIMISTIC/REDIS ghi nhận 2 đơn và stock 8; OPTIMISTIC ghi nhận 1 đơn và stock 9. Các số liệu đều khớp DB và log; không còn lỗi đòi bán hết 10 đơn khi chỉ có 2 request.

## Bằng chứng, tái chạy và giới hạn

- [comparison.csv](comparison.csv) chứa số đếm DB/HTTP/log và phân vị mỗi lượt; chỉ `round > 0` là lượt đo. Tệp `*-stock.csv` là kết quả truy vấn PostgreSQL độc lập, các cột `node0_orders`/`node1_orders` dùng tiền tố khóa idempotency để phân loại đơn đã ghi nhận theo node.
- Các `round-*.json.gz` giữ sự kiện k6 gốc, kèm JSON tổng hợp và đầu ra dòng lệnh. Log hai instance chứa requestId để đối chiếu. `containers.txt` ghi cùng image nhưng hai container khác nhau, đúng giới hạn tài nguyên.
- [ENVIRONMENT.md](ENVIRONMENT.md) ghi cấu hình máy, phiên bản, hash JAR/image và lệnh tái chạy. `SHA256SUMS.txt` đi kèm dữ liệu gốc. Container, mạng và volume của cả hai lượt chạy đều đã được dọn.
- Chưa chạy workflow GitHub từ xa, chưa kiểm chứng trên Linux/macOS hoặc với tải cao/kéo dài. Chưa kiểm tra mất mạng, dừng instance giữa giao dịch hay nhiều request đồng thời dùng cùng một Idempotency-Key; các request của bài đo có khóa riêng. Không suy diễn thành bảo đảm xử lý đúng mọi sự cố phân tán.
- HTTP/API hiện có giữ nguyên; chỉ thêm header nhận diện và trường requestId cho ErrorResponse. Không có dữ liệu đặt hàng thật hoặc DB cục bộ bị reset. Không thay các kết quả đo Giai đoạn 0–1.
- Tại thời điểm thu thập bằng chứng, công việc chưa commit/push theo yêu cầu. Đợt commit local sau đó không thay đổi các số đo đã ghi; không tự đẩy mã lên kho từ xa. `monolith-v1` chưa được tạo; Giai đoạn 3 chưa bắt đầu.

Nguồn thiết kế: [Spring OncePerRequestFilter](https://docs.spring.io/spring-framework/docs/6.2.x/javadoc-api/org/springframework/web/filter/OncePerRequestFilter.html), [Spring Boot logging](https://docs.spring.io/spring-boot/3.5/reference/features/logging.html), [Logback MDC theo thread](https://logback.qos.ch/manual/mdc.html). MDC được sao chép rõ ràng sang worker vì thread con không tự thừa hưởng context của thread gọi.
