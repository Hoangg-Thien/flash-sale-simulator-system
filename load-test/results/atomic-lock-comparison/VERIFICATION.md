# Kiểm chứng chiến lược ATOMIC

Phạm vi: chỉ Giai đoạn 1 của kế hoạch chuyển đổi phiên bản 2. SHA bắt đầu `59c1740ba51f93b1524e42e3ef30a22d25d22121`, nhánh `feature/hardening`. Không tạo commit, không đẩy mã lên kho từ xa, không thay đổi Giai đoạn 2 hoặc tách thành vi dịch vụ.

## Triển khai và đối chiếu kế hoạch

Mã nguồn khớp với thiết kế ứng dụng nguyên khối/các chiến lược trong kế hoạch và giữ nguyên cơ chế bao khóa Redis của Giai đoạn 0. Có một bổ sung cần thiết chưa được liệt kê trong Giai đoạn 1: các ràng buộc CHECK hiện tại trên `orders.lock_mode` và `simulation_runs.lock_mode` từ chối ATOMIC. Điểm này đã được giải thích trước khi triển khai và xử lý bằng bản nâng cấp lược đồ V7 mới; không sửa V1–V6.

| Tệp | Thay đổi |
|---|---|
| `enums/LockMode.java`, `concurrency/AtomicUpdateStrategy.java` | Thêm chiến lược thứ năm; phương thức bao mặc định có sẵn tiếp tục thực thi giao dịch bình thường |
| `repository/InventoryRepository.java` | UPDATE SQL trực tiếp có điều kiện, trả số dòng bị tác động, tăng version, cập nhật `update_at` bằng đồng hồ ứng dụng; ghi các thay đổi đang chờ xuống cơ sở dữ liệu rồi xóa ngữ cảnh lưu trữ JPA |
| `service/OrderService.java`, `service/SimulationService.java` | Chọn ATOMIC và thêm vào thông báo các chế độ được hỗ trợ; giữ nguyên cấu trúc API và xử lý giao dịch/lỗi |
| `db/migration/V7__add_atomic_lock_mode.sql` | Mở rộng hai ràng buộc về chế độ được lưu trong cơ sở dữ liệu |
| `AtomicStrategyIntegrationTest.java`, `AtomicMigrationIntegrationTest.java` | Kiểm thử đồng thời, trạng thái, bộ nhớ đệm, tương thích khóa lạc quan và nâng cấp V6 lên V7 với dữ liệu có sẵn |
| `OrderServiceIntegrationTest.java` | Chạy các trường hợp thành công, hết hàng, trùng khóa, thiếu sản phẩm và lưu giá tại thời điểm mua cho cả năm chế độ |
| `TransactionRollbackIntegrationTest.java` | Thêm ATOMIC; yêu cầu lỗi lưu đơn được chủ động tạo phải là nguyên nhân gốc, tránh trường hợp chế độ chưa được hỗ trợ vẫn khiến kiểm thử đạt nhầm |
| `SimulationServiceIntegrationTest.java`, `SimulationComparisonIntegrationTest.java` | Mô phỏng ATOMIC với tồn kho 0/1/10 và so sánh cả năm chế độ |
| `RedisFailureIntegrationTest.java` | Kiểm tra mua hàng bằng ATOMIC vẫn hoạt động sau khi Redis dừng |
| `load-test/purchase-atomic.js`, `comparison.js`, `run-all.ps1`, `run-all.sh` | Kịch bản ATOMIC riêng, chế độ thứ năm trong kịch bản so sánh và bổ sung vào cả hai kịch bản chạy toàn bộ |
| `.github/workflows/load-test.yml` | Thêm lựa chọn atomic khi kích hoạt thủ công và nhánh thực thi tương ứng; kịch bản chạy toàn bộ cũng có ATOMIC |
| `docs/adr/ADR-001-inventory-locking-strategies.md` | Quyết định thiết kế, các chiến lược thay thế, ảnh hưởng đến ngữ cảnh lưu trữ/giao dịch và tài liệu chính thức |
| `README.md` | Thêm hướng dẫn dùng ATOMIC và liên kết tới số đo thực tế thay cho ví dụ bốn chế độ trước đây |

Giữ nguyên thay đổi có sẵn ở `docker-compose.yml` tại thư mục gốc và các tệp kế hoạch. Chiến lược Redis, giao diện bao chiến lược và kiểm thử thứ tự khóa/giao dịch Redis không có khác biệt so với commit bắt đầu.

## Bằng chứng kiểm thử

- **Trước khi sửa mã ứng dụng (RED):** `./mvnw.cmd '-Dtest=AtomicStrategyIntegrationTest' test` đã biên dịch và thực thi 20 trường hợp; do chưa hỗ trợ ATOMIC, có 3 điều kiện kiểm tra không đạt và 17 lỗi thực thi (chế độ chưa được hỗ trợ), không có trường hợp bỏ qua. Xem [atomic-red.txt](atomic-red.txt); nhật ký đầy đủ trên máy là `atomic-red.log`.
- **Nhóm kiểm thử sau khi sửa (GREEN):** `./mvnw.cmd '-Dtest=AtomicStrategyIntegrationTest,TransactionRollbackIntegrationTest,SimulationComparisonIntegrationTest,RedisLockTransactionIntegrationTest' test`: **30/30 đạt**, không có điều kiện kiểm tra thất bại, lỗi thực thi hoặc trường hợp bỏ qua.
- **Biên dịch toàn bộ lần cuối:** `./mvnw.cmd clean package`: **phát hiện 84 trường hợp, 83 đạt, 1 bỏ qua, không có điều kiện kiểm tra thất bại hoặc lỗi thực thi; BUILD SUCCESS**. Xem [suite.txt](suite.txt). `FlashSaleApplicationTests.contextLoads` đã bị vô hiệu hóa từ trước. Các cảnh báo có sẵn về tác nhân Mockito và SpyBean bị đánh dấu ngừng dùng vẫn còn.
- **Mua đồng thời bằng ATOMIC:** 20 người mua đồng thời với tồn kho 0/1/10 và số lượng mua 1/3/10, cộng thêm 10 vòng lặp với tồn kho 10. Số yêu cầu thành công chính xác là `floor(initialStock / quantity)`, tồn kho cuối được bảo toàn và không âm, version tăng một lần cho mỗi lượt trừ kho thành công, số đơn đã ghi nhận bằng số yêu cầu thành công. Lỗi bất ngờ của tác vụ được truyền ra qua Future thay vì bị tính thành hết hàng.
- **Trạng thái và ngữ cảnh lưu trữ:** thành công cập nhật tồn kho/version/thời gian; UPDATE không tác động dòng nào thì cả ba giữ nguyên; thiếu bản ghi kho/sản phẩm phát sinh ProductNotFoundException; đọc Inventory do JPA quản lý vẫn nhận dữ liệu mới sau hai lần trừ kho trong cùng giao dịch, thay đổi Product đang chờ không bị mất khi ghi xuống rồi xóa ngữ cảnh; thao tác gộp một thực thể cũ bằng khóa lạc quan bị từ chối sau khi ATOMIC đã commit.
- **Tương thích:** giữ nguyên cấu trúc đơn hàng/DTO của API, cách xử lý khóa chống tạo đơn lặp, giá tại thời điểm mua, hành vi hoàn tác giao dịch và các kiểm thử Redis Giai đoạn 0. ATOMIC hoạt động sau sự cố Redis. Kiểm thử nâng cấp đưa lược đồ V6 có dữ liệu lên V7, giữ nguyên đơn/tồn kho cũ và tiếp tục từ chối chế độ không hợp lệ trên cả hai bảng.
- **Kịch bản:** `k6 inspect` nhận diện cả hai kịch bản k6 có thay đổi và đủ năm chế độ so sánh; Bash `-n`, bộ phân tích cú pháp PowerShell và `git diff --check` đều đạt. Quy trình GitHub được rà soát tĩnh, chưa chạy từ xa. Chưa thực thi toàn bộ kịch bản run-all hoặc tải mặc định 80 giây của kịch bản riêng.
- **Kiểm tra nhanh kịch bản riêng:** `purchase-atomic.js`, tham số dòng lệnh `--stage 5s:20`: mã thoát 0, **10 lượt mua thành công**, tồn kho cuối 0, **2,628 phép kiểm tra đạt / 0 thất bại**. Xem tệp tổng hợp và đầu ra dòng lệnh tương ứng. Hai yêu cầu bị ghi nhận là lỗi HTTP thực chất là phản hồi HTTP 200 thành công khi đặt lại/dọn dẹp, bị hàm phân loại phản hồi dùng chung có sẵn xem là ngoài dự kiến.
- **Rà soát độc lập:** không có phát hiện mức nghiêm trọng hoặc quan trọng trong phần triển khai, nâng cấp lược đồ hoặc kiểm thử hồi quy. Có thể cải thiện sau bằng ngưỡng kiểm tra riêng cho tổng số yêu cầu thành công để tăng độ bảo đảm cho kịch bản riêng, ngoài việc kiểm tra hết hàng; tính bảo toàn số đơn đã được kiểm tra trong kiểm thử tích hợp và số đếm từ cơ sở dữ liệu của các lượt so sánh.

## Số đo so sánh năm chế độ

Chạy một lượt làm nóng đầy đủ trước ba lượt đo liên tiếp. Mỗi lượt đều trả mã thoát **0**, đạt ngưỡng tỷ lệ lỗi HTTP có sẵn và đạt các phép đối chiếu độc lập về tồn kho/đơn hàng trong cơ sở dữ liệu.

| Lượt | Số yêu cầu HTTP | Yêu cầu HTTP/giây | p50 ms | p95 ms | p99 ms | Tỷ lệ lỗi HTTP do k6 ghi nhận (%) | Phép kiểm tra thất bại |
|---|---:|---:|---:|---:|---:|---:|---:|
| 1 | 118202 | 976.23 | 5.60 | 67.15 | 79.47 | 0.0085 | 0 |
| 2 | 119038 | 983.19 | 5.84 | 66.42 | 73.38 | 0.0084 | 0 |
| 3 | 118996 | 982.85 | 5.77 | 66.49 | 74.53 | 0.0084 | 0 |

Bảng tổng hợp cả năm chế độ cùng các yêu cầu chuẩn bị/dọn dẹp; không phải bảng xếp hạng tốc độ từng chiến lược. Mười yêu cầu bị ghi nhận là lỗi HTTP trong mỗi lượt đều là phản hồi HTTP 200 thành công khi đặt lại/dọn dẹp, bị hàm phân loại có sẵn xem là ngoài dự kiến; các phép kiểm tra mua hàng không thất bại.

Mỗi lượt đo ghi nhận **25 đơn thành công**, năm đơn mỗi chế độ. Số đếm độc lập từ PostgreSQL trong `comparison-N-stock.csv` cho thấy tồn kho 0 và năm đơn thành công đã được ghi nhận đối với ATOMIC, OPTIMISTIC, PESSIMISTIC và REDIS. Như vậy, mọi chế độ an toàn đều thỏa `confirmed + remaining = 5`, không bán vượt tồn kho hoặc thất thoát số lượng không được giải thích. NONE cũng tình cờ có năm yêu cầu thành công trong kịch bản tăng tải này; điều đó không chứng minh NONE đúng.

Dữ liệu gốc: [lượt 1](comparison-1.json.gz), [lượt 2](comparison-2.json.gz), [lượt 3](comparison-3.json.gz). Các tệp JSON tổng hợp, đầu ra dòng lệnh, CSV tồn kho và [mã thoát](run-status.txt) tương ứng được lưu cùng thư mục. Từng tệp gzip đều được đọc thành công đến cuối và đã tạo [mã kiểm tra SHA-256](SHA256SUMS.txt). Mã kiểm tra của kết quả cơ sở Redis trước đó không thay đổi. Các container, mạng và vùng lưu trữ riêng dùng cho phép đo đã được xóa sau khi thu thập.

Toàn bộ công việc triển khai và kiểm chứng trên máy cho Giai đoạn 1 đã hoàn tất. Chưa kích hoạt quy trình GitHub từ xa, chạy toàn bộ kịch bản run-all hoặc chạy tải mặc định 80 giây của kịch bản riêng. Không tạo commit hoặc đẩy mã lên kho từ xa.

## Cách chạy lại và giới hạn còn lại

Các lệnh đầy đủ để biên dịch, chạy nhóm kiểm thử, khởi động hệ thống, chờ trạng thái sẵn sàng, làm nóng, đo ba lượt và dọn dẹp nằm trong [ENVIRONMENT.md](ENVIRONMENT.md). Phép đo dùng dự án Compose và cơ sở dữ liệu riêng; không cần đặt lại cơ sở dữ liệu cục bộ hoặc cơ sở dữ liệu đang vận hành thực tế.

ATOMIC vẫn lấy khóa hàng trong PostgreSQL, có thể phải chờ hoặc thất bại do lỗi cơ sở dữ liệu và chưa có vòng thử lại. Việc xóa ngữ cảnh lưu trữ tách các thực thể đang được quản lý sau khi ghi các thay đổi đang chờ xuống cơ sở dữ liệu; nơi gọi muốn dùng lại tham chiếu thực thể cũ phải tải lại dữ liệu. Giả định không có giao dịch bao ngoài của cơ chế khóa Redis hiện tại được giữ nguyên. V7 lấy khóa bảng khi thay đổi/kiểm tra hai ràng buộc CHECK; chưa có bảo đảm nâng cấp trực tuyến không gián đoạn.

Các kịch bản k6 chủ yếu đo phản hồi hết hàng, chưa xác định được năng lực xử lý đơn thành công hoặc thứ hạng tốc độ. Kịch bản riêng không tự đếm độc lập số đơn đã ghi nhận trong cơ sở dữ liệu; các lượt so sánh có thực hiện việc đó. Ứng dụng vẫn khởi tạo Redisson lúc bắt đầu dù luồng mua bằng ATOMIC không gọi Redis. Việc kiểm chứng nhiều phiên ứng dụng và Giai đoạn 2 được để lại cho bước sau.
