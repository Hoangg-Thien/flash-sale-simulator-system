# Kiểm chứng tự động nhiều phiên ứng dụng

Hai tiến trình Spring Boot độc lập cùng dùng PostgreSQL và Redis. Không có dịch vụ mới hoặc API Gateway.

## Kiểm thử hồi quy bằng hai JVM thật

Cần JDK 21 và Docker Desktop:

```powershell
.\mvnw.cmd clean package
# Hoặc chạy riêng nhóm nhiều JVM và requestId/MDC:
.\mvnw.cmd '-Dtest=MultiInstanceIntegrationTest,RequestCorrelationIntegrationTest' test
```

Kiểm thử khởi động hai JVM với hai cổng ngẫu nhiên, dùng chung PostgreSQL/Redis qua Testcontainers. Mỗi đợt có 20 người mua bắt đầu đồng thời, chia 10 yêu cầu cho mỗi JVM. Kết quả được đối chiếu với số đơn, khóa idempotency, tổng số lượng mua và tồn kho trong DB; cả hai API phải trả cùng tồn kho đã lưu. NONE là trường hợp riêng, dùng sản phẩm riêng để tái hiện mất cập nhật.

## So sánh k6 và đối chiếu DB trên hai container

Cần Docker Compose >= 2.24.4, k6 và PowerShell 5 hoặc 7. Chạy tại thư mục gốc:

```powershell
powershell -NoProfile -File load-test/test-multi-instance-verification.ps1
powershell -NoProfile -File load-test/run-multi-instance.ps1
# Muốn ghi thêm bằng chứng NONE không an toàn, chọn rõ tùy chọn này:
powershell -NoProfile -File load-test/run-multi-instance.ps1 -IncludeUnsafe
```

Trên Linux/macOS, dùng `pwsh` thay `powershell`. Sao lưu kết quả trước khi chạy lại vì các tệp đầu ra sẽ bị ghi đè.

Runner tự build, khởi động và dọn project Compose riêng `flashsale-multi-proof`. Cổng kiểm chứng là 18080/18081; PostgreSQL/Redis không công khai cổng. Cơ sở dữ liệu và vùng lưu trữ của project này chỉ dành cho phép đo, được xóa khi kết thúc; không dùng dữ liệu Compose cục bộ đang có. Nếu project này đang tồn tại, runner dừng để tránh đụng vào lượt chạy khác.

Mặc định mỗi chế độ ATOMIC, OPTIMISTIC, PESSIMISTIC, REDIS có một lượt làm nóng và ba lượt đo: 20 người dùng ảo, mỗi người gửi một yêu cầu, stock 10, số lượng mua 1. `-Users`, `-InitialStock`, `-Quantity`, `-Rounds` cho phép thay cấu hình. NONE chỉ chạy khi có `-IncludeUnsafe`, dùng sản phẩm riêng stock 1, số lượng mua 1; phải quan sát bán vượt kho ở ít nhất một lượt đo mới được coi là tái hiện thành công.

Kịch bản [multi-instance.js](load-test/multi-instance.js) nhận `BASE_URLS` gồm hai URL khác nhau. Nó kiểm tra hai instanceId khác nhau, phản hồi đúng instance, requestId được truyền lại và stock cuối giống nhau. Runner còn kiểm tra số đơn/tổng số lượng trong PostgreSQL khớp HTTP, không có khóa idempotency trùng, stock được bảo toàn ở chế độ an toàn, cùng số dòng log mua hàng trên từng container khớp số request đã gửi. OPTIMISTIC có thể còn hàng vì xung đột bị từ chối mà không thử lại.

Đầu ra nằm trong [multi-instance-proof](load-test/results/multi-instance-proof/): raw JSON nén, JSON tổng hợp, CSV từ DB, bảng so sánh và log của từng instance. Đây là phép thử tải đồng thời ngắn để kiểm chứng tính đúng đắn, không phải ước tính công suất phục vụ thực tế.

## Nhận diện yêu cầu và instance

Root `docker-compose.yml` chạy `app-a` ở 8080 và `app-b` ở 8081. Mỗi phản hồi có `X-Instance-Id` và `X-Request-Id`. Header `X-Request-Id` hợp lệ được giữ nguyên; nếu thiếu hoặc không hợp lệ thì tạo UUID. ID dài tối đa 128 ký tự, chỉ gồm chữ, số, `.`, `_`, `:`, `-`, bắt đầu bằng chữ hoặc số.

Các phản hồi lỗi dạng `ErrorResponse` có thêm `requestId`; các mã HTTP và trường cũ được giữ nguyên. Log có `instanceId`/`requestId`, MDC được dọn sau request và được truyền sang worker mô phỏng.

Workflow load-test chỉ kích hoạt thủ công; chọn `multi-instance` để chạy bốn chế độ an toàn và đối chiếu DB. Không đưa k6 vào pipeline pull request.
