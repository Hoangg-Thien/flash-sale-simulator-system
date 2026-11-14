# Môi trường đo hiệu năng ATOMIC và các chế độ khóa hiện có

- Ngày đo: 2026-11-14, múi giờ Asia/Bangkok. Commit bắt đầu: `59c1740ba51f93b1524e42e3ef30a22d25d22121`, nhánh `feature/hardening`, kèm phần triển khai ATOMIC chưa commit tại thời điểm đo. Không tạo commit hoặc đẩy mã lên kho từ xa trong quá trình thực hiện.
- Windows 11 x64; AMD Ryzen 5 7535HS, 6 nhân / 12 CPU logic, RAM 15.32 GiB. Docker Desktop dùng bộ máy Linux phiên bản 29.6.2: 12 CPU và RAM khả dụng 7,968,636,928 byte. Các ứng dụng khác và IDE vẫn chạy.
- Biên dịch và kiểm thử: Oracle JDK 21.0.2, Maven wrapper 3.9.16, Spring Boot 3.5.13. Môi trường chạy: Temurin 21.0.13+9; PostgreSQL 17.9; Redis 7.4.11. k6 2.2.0 (`00a9a1b7f5`, chạy trực tiếp trên Windows amd64).
- Tái sử dụng [compose.yml](../redis-lock-baseline/compose.yml) dùng để đo trước đó, với tên dự án riêng `flashsale-atomic`. Một phiên ứng dụng tại `http://127.0.0.1:18080`; không dùng cơ sở dữ liệu cục bộ hoặc vùng lưu trữ Compose có tên đang tồn tại. Giữ nguyên các tệp kết quả cơ sở của Redis.
- Giới hạn tài nguyên: ứng dụng 2 CPU / 1 GiB, PostgreSQL 2 CPU / 1 GiB, Redis 1 CPU / 256 MiB. k6 chạy trên máy chủ, không giới hạn tài nguyên. JVM dùng `-XX:MaxRAMPercentage=75.0`; tắt đầu ra SQL; mức ghi nhật ký gốc và SQL của Hibernate là WARN.
- SHA-256 của tệp JAR đã kiểm thử: `19D000EE5F741874890366104A3360D72DEDD6CA10C1C742C50EF2B0F9032540`.
- Kịch bản: `load-test/comparison.js`, đã có NONE, OPTIMISTIC, PESSIMISTIC, REDIS và ATOMIC. Tồn kho ban đầu 5 mỗi chế độ, số lượng mua 1, tối đa 20 người dùng ảo. Mỗi chế độ tăng tải 3 giây, giữ tải 15 giây, giảm tải 3 giây; bắt đầu ở giây 0/25/50/75/100, tổng thời gian dự kiến 121 giây.
- Chạy một lượt làm nóng đầy đủ cho cả năm chế độ, sau đó đo ba lượt so sánh liên tiếp. Xóa dữ liệu các bảng, đặt lại bộ đếm định danh và xóa dữ liệu Redis riêng trước mỗi lượt đo; JVM tiếp tục chạy. Không chạy kiểm thử tích hợp trong quá trình đo hiệu năng.
- Riêng `purchase-atomic.js` được kiểm tra nhanh với tham số dòng lệnh ghi đè giai đoạn tải `--stage 5s:20`. Lượt này kiểm tra khâu chuẩn bị, mua hàng và dọn dẹp, không phải tải mặc định 80 giây / 100 người dùng ảo của kịch bản.
- Số liệu gốc được lưu dạng JSON Lines nén gzip, kèm JSON tổng hợp, đầu ra dòng lệnh và CSV đếm độc lập số đơn đã ghi nhận/tồn kho cuối cho mỗi lượt đo. Các tệp số đo có mã kiểm tra SHA-256 đi kèm.

Mã định danh nội dung các ảnh container dùng khi chạy:

```text
eclipse-temurin@sha256:ac05c3c42b7c2f1a52c9bd52f01041fa1958231b15ebeae7420429cbc5baf474
postgres@sha256:b994732fcf33f73776c65d3a5bf1f80c00120ba5007e8ab90307b1a743c1fc16
redis@sha256:ff02b58f971e7d7d156a1267e283fcbbeee91773b6aa36c49dac28ecfe28eadf
```

## Cách chạy lại từ thư mục gốc của kho mã

Cần Docker Desktop và k6. Sao lưu đầu ra cũ trong thư mục này trước; kịch bản đo ghi đè các tệp so sánh và ghi nối tiếp vào `run-status.txt`.

```powershell
.\mvnw.cmd clean package
# Có thể chạy riêng nhóm kiểm thử sau; clean package đã chạy các kiểm thử này:
.\mvnw.cmd '-Dtest=AtomicStrategyIntegrationTest,AtomicMigrationIntegrationTest,OrderServiceIntegrationTest,TransactionRollbackIntegrationTest,SimulationServiceIntegrationTest,SimulationComparisonIntegrationTest,RedisLockTransactionIntegrationTest,RedisFailureIntegrationTest' test

docker compose -p flashsale-atomic -f load-test/results/redis-lock-baseline/compose.yml up -d
$ready = $false
for ($attempt = 0; $attempt -lt 60; $attempt++) {
    try {
        if ((Invoke-RestMethod http://127.0.0.1:18080/actuator/health).status -eq 'UP') {
            $ready = $true
            break
        }
    } catch {}
    Start-Sleep -Seconds 1
}
if (-not $ready) { throw 'Benchmark app did not become healthy' }

k6 run --quiet --stage 5s:20 --env BASE_URL=http://127.0.0.1:18080 --summary-export load-test/results/atomic-lock-comparison/purchase-atomic-summary.json load-test/purchase-atomic.js
k6 run --quiet --env BASE_URL=http://127.0.0.1:18080 load-test/comparison.js
powershell -NoProfile -File load-test/results/atomic-lock-comparison/run-comparison.ps1
docker compose -p flashsale-atomic -f load-test/results/redis-lock-baseline/compose.yml down -v
```

Cố định ảnh container theo các mã định danh đã ghi trong tệp Compose phục vụ phép đo để tái tạo đúng phiên bản môi trường. Vẫn thực hiện dọn dẹp nếu lượt đo thất bại. Quy trình GitHub Actions đã được cập nhật và kiểm tra trên máy, chưa được kích hoạt từ xa. Chưa chạy toàn bộ các bộ kịch bản `run-all`; đã kiểm tra cú pháp shell/PowerShell và kiểm tra/thực thi riêng cả hai kịch bản k6 có thay đổi.

## Cách diễn giải kết quả

Số đo trên máy cá nhân này chủ yếu đo phản hồi hết hàng vì tồn kho chỉ có 5 (10 đối với kịch bản ATOMIC riêng). Phần hiển thị bán vượt tồn kho có sẵn của kịch bản so sánh chỉ kiểm tra tồn kho âm, nên số đếm độc lập từ cơ sở dữ liệu mới là bằng chứng tính đúng đắn: mọi chế độ an toàn phải thỏa `confirmed + final_stock = 5`, `confirmed <= 5` và `final_stock >= 0`. NONE được chủ động loại khỏi các điều kiện kiểm tra dành cho chế độ an toàn. OPTIMISTIC có thể từ chối yêu cầu bị xung đột trước khi hàng bán hết; việc gửi tiếp nhiều yêu cầu trong kịch bản này có thể che khuất hiện tượng còn hàng nhưng yêu cầu bị từ chối.

Hàm phân loại phản hồi dùng chung của k6 coi các phản hồi HTTP 200 thành công khi đặt lại/dọn dẹp là ngoài dự kiến. Cần phân biệt số lỗi nhỏ do cơ chế có sẵn này ghi nhận với các phép kiểm tra mua hàng thực sự thất bại. Báo cáo không đưa ra xếp hạng tốc độ, ước tính năng lực phục vụ thực tế hoặc bằng chứng chạy nhiều phiên ứng dụng.
