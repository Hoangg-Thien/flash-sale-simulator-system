# Kiểm thử độ tin cậy của luồng mua hàng

## Kiểm tra thiết kế trước khi gây lỗi

| Thời điểm tiến trình dừng | Dữ liệu còn lại | Quyết định hợp lệ khi phục hồi |
|---|---|---|
| Order đã ghi đơn và dòng hàng, chưa gửi REST | PENDING, có thể chưa có reservation | Tra cứu bằng orderId. Trước hạn, gửi lại cùng payload khi nhận 404. |
| Inventory đã commit nhưng Order chưa nhận phản hồi | PENDING và RESERVED, tồn đã giảm | Tra cứu để xác nhận. Gửi lại reserve với cùng ID cũng chỉ đọc kết quả đã lưu. |
| Bộ đối soát và luồng mua cùng muốn kết thúc | Một bên có thể đang giữ phản hồi RESERVED cũ | Câu UPDATE xác nhận đòi PENDING và cancel_requested=false. Bộ đối soát phải ghi ý định hủy thành công trước khi gọi release. |
| Inventory đã hoàn kho nhưng phản hồi release mất | PENDING với ý định hủy bền vững, reservation đã RELEASED | Gửi lại release. Chỉ ghi REJECTED khi Inventory xác nhận RELEASED hoặc REJECTED. |
| Redis ngừng đáp ứng | Chế độ REDIS có thể không lấy được khóa | Bảo toàn số lượng, thử lại sau phục hồi. ATOMIC tiếp tục dùng PostgreSQL. |

Order dùng giao dịch cục bộ khi tạo đơn và chụp giá. HTTP nằm ngoài giao dịch. Inventory dùng một giao dịch cho bản ghi giữ hàng và biến động tồn. Unique index chặn yêu cầu trùng ở mỗi DB. Mã kiểm thử chặn đúng lúc tuần tự hóa phản hồi để có thể đọc bằng kết nối PostgreSQL độc lập rồi mới gửi SIGKILL. Lớp chặn chỉ nằm trong test classpath.

Một truy vấn 404 có thể chạy trước khi yêu cầu reserve đến. Giải phóng sớm tạo tombstone để yêu cầu đến muộn không thể giữ hàng. Phép đối chiếu phải nhận danh sách ID lịch sử từ ảnh chụp lúc chuyển dữ liệu. Suy đoán đơn cũ chỉ vì thiếu reservation sẽ che giấu lỗi ở đơn mới.

## Vì sao số ca đạt giảm từ 115 xuống 25

Bộ Order trước khi tích hợp dùng lại nguyên 107 ca của monolith, trong đó một ca context đã bị vô hiệu hóa. Inventory có 9 ca, tổng cộng 115 ca đạt trên 116 ca được liệt kê. Có thể kiểm tra tệp kiểm thử hai mốc bằng `git diff --find-renames monolith-v1 6683386 -- '*Test.java'`. Sau tích hợp, Order có 15 ca đạt và một bỏ qua, Inventory có 10 ca đạt. Số lần chạy của một phương thức có tham số được tính riêng.

| Nhóm cũ | Phạm vi cần giữ sau chuyển đổi |
|---|---|
| AtomicStrategy (20), Concurrency (4), MultiInstance (17), LockStrategyUnit (7) | Các phép cập nhật JPA và cache persistence context cũ chỉ còn ở tag. Kiểm tra số lượng 0/1/10, phiên bản, nhiều tiến trình và nhiều chế độ tại Inventory thật. Chế độ NONE chỉ chạy riêng để tái hiện sai số. |
| OrderService (26), TransactionRollback (6), ProductRepository (2) | Bỏ yêu cầu rollback đồng thời hai DB. Kiểm tra rollback riêng lẻ tại hai chủ sở hữu, snapshot giá, khóa idempotency, mã lỗi, sản phẩm không tồn tại và kiểm tra payload. |
| RedisFailure (1), RedisLockTransaction (3) | Làm mất Redis, chặn trigger tại COMMIT trong PostgreSQL, quan sát khóa thật trong Redis ở cả nhánh thành công và rollback. |
| SimulationComparison (1), SimulationService (12), RequestCorrelation (6) | Chế độ hiện được cấu hình theo deployment. Chạy bốn cấu hình, kiểm tra kết quả mô phỏng đã lưu và MDC ở worker con. |
| Migration (1), context (1) | Giữ bài nâng cấp schema và ca context đã bỏ qua từ trước. Diễn tập cutover và restore bằng dữ liệu đã bán. |

Đối chiếu lịch sử trong [bộ monolith](../load-test/results/multi-instance-proof/suite.txt) và [bộ Order–Inventory trước kiểm toán](../load-test/results/reliability/reactor-before.txt).

## Lỗi được tái hiện

Order từng trả 201 và tiêu thụ hàng khi nhận `quantity: 1.9`. Jackson chuyển số thực sang số nguyên trước Bean Validation. Bài kiểm thử đỏ ghi nhận 201 thay vì 400. Sau khi tắt ép kiểu, exception handler tổng quát trả 500. Handler chuyên biệt hiện trả 400 cho body không thể đọc. Kiểm thử xác nhận cả productId và quantity sai định dạng đều bị từ chối trước khi tạo đơn hoặc gọi Inventory. [Bằng chứng trước khi sửa](../load-test/results/reliability/fractional-before.txt)

## Điều kiện sao lưu và phục hồi

1. Chặn yêu cầu mới, dừng toàn bộ Order, scheduler, Inventory, công cụ nạp dữ liệu và client SQL có quyền ghi. Chờ các giao dịch đang chạy kết thúc hoặc rollback. Giữ hàng rào ghi đến khi xong **cả hai** bản dump. Hai lệnh pg_dump liên tiếp trên hệ thống còn ghi có thể tạo cặp snapshot sai.
2. Lưu hai bản `pg_dump -Fc` cùng manifest tồn đầu kỳ theo mã sản phẩm, ID lịch sử và checksum cho từng bản. Khi vận hành cần lưu cả cặp và manifest vào kho sao lưu bền vững. Script lưu cặp dump tổng hợp cùng manifest vào thư mục mới cạnh báo cáo, đọc lại từng tệp và so checksum trước khi restore. Dùng tên báo cáo mới cho mỗi lượt.
3. Khôi phục vào hai DB **trống**, dịch vụ vẫn dừng. Dùng `pg_restore --single-transaction --exit-on-error --no-owner --no-privileges` cho mỗi DB, so sánh hai ảnh chụp nghiệp vụ với nguồn. Script cũng so hash nội dung từng bảng public cùng last_value và is_called của từng sequence. Cặp restore lỗi một phần phải được loại khỏi quy trình mở ghi.
4. Khởi động hai dịch vụ với đúng cặp DB. Đợi đối soát, phát lại khóa đã mua và kiểm tra tồn từng SKU, bản ghi đơn–reservation, số đơn chờ. Chỉ mở lại lưu lượng sau khi đối soát đạt và backlog nằm trong giới hạn vận hành.

Ca diễn tập mua 3 đơn vị từ tồn 20 bằng monolith trước cutover, sau đó mang tồn 17 sang Inventory. Có một đơn chờ lúc sao lưu. Sau phục hồi còn 11 đơn vị, ba đơn CONFIRMED (một đơn lịch sử), một đơn REJECTED, hai reservation RESERVED, một reservation REJECTED. [Ảnh chụp và checksum](../load-test/results/reliability/restore.json)

## Ngưỡng tuổi đơn

Trên máy thử nghiệm, đặt mục tiêu đơn tồn tại ở trạng thái PENDING tối đa **90 giây tính từ requested_at**, với backlog 96 đơn và một gián đoạn ngắn, bao gồm thời gian khởi động lại. Đo `completed_at - requested_at` bằng đồng hồ Order DB. Khi chưa hoàn tất, đo `now() - requested_at` của đơn cũ nhất. Quá ngưỡng cần kiểm tra kết nối, tốc độ xử lý và lỗi lặp. Tiếp tục giữ PENDING khi chưa có bằng chứng hủy. Batch 32 đơn nối tiếp với timeout và retry hữu hạn cho mỗi lệnh không tạo ra giới hạn trên cho tổng tuổi đơn trong một sự cố kéo dài.

## Tái hiện trên project Compose riêng

Chạy từ thư mục gốc với Java 21, Maven, Python 3, Docker và Compose. Các script dùng project ngẫu nhiên và xóa volume của chính project đó sau khi chạy. Tạo ảnh runtime trước khi diễn tập.

```bash
mvn -B clean verify
docker compose build
bash load-test/benchmark/prepare.sh
LEGACY_IMAGE=flashsale-bench-monolith:3ae96ef python3 -B scripts/reliability/restore_pair.py /tmp/restore.json
python3 -B scripts/reliability/faults.py /tmp/faults.json
python3 -B scripts/reliability/test_verify_databases.py
for mode in OPTIMISTIC PESSIMISTIC REDIS; do
  mvn -B -pl services/inventory-service -Dinventory.test.lock-mode=$mode test || exit 1
done
mvn -B -pl services/inventory-service -Dinventory.test.lock-mode=NONE \
  '-Dtest=ReservationIntegrationTest#unsafeLabModeCanCommitMoreReservationsThanStock' test
```

Trên project Compose gốc đã dừng toàn bộ writer, đối chiếu bằng `python3 -B scripts/reliability/verify_databases.py TEN_PROJECT manifest.json`. Ví dụ manifest là `{"opening_stock":{"1":17},"historical_ids":[1]}`. Phải có tồn đầu kỳ cho mỗi SKU và không có thao tác reset/admin sau mốc đó. Công cụ trả mã thoát khác 0 nếu có sai lệch hoặc còn đơn chờ. Dùng `--allow-pending` khi chụp ngay giữa sự cố để vẫn kiểm tra các bất biến còn lại. Các snapshot giả lập trong bộ kiểm thử âm xác nhận công cụ phát hiện tồn sai, payload sai, đơn bị gán nhầm nhánh lịch sử và trạng thái không tương thích.

## Redis và khởi động lại

Một ca hồi quy chạy Redis với tiến trình không phục vụ và healthcheck thất bại. Compose ban đầu chặn khởi động Inventory ATOMIC mặc dù chiến lược này chỉ cần PostgreSQL. Đã bỏ điều kiện chờ Redis ở cấu hình Inventory. Khi chạy toàn bộ stack, Compose vẫn khởi động Redis. Nếu chỉ chọn riêng một bản sao với chế độ REDIS, cần chọn thêm service `redis`; ứng dụng dùng kết nối Redisson lazy và đơn còn chờ khi Redis không dùng được. [Kết quả đỏ trước khi sửa](../load-test/results/reliability/cold-start-before.txt)

```bash
python3 -B scripts/reliability/cold_start.py
python3 -B scripts/reliability/redis_recovery.py /tmp/redis.json
```

## Kết quả kiểm thử

| Kịch bản | Bằng chứng và kết quả |
|---|---|
| Toàn bộ reactor, chế độ mặc định ATOMIC | Order 20 đạt, 1 bỏ qua từ trước. Inventory 14 đạt, 2 mục chỉ chạy khi chọn REDIS hoặc NONE. Ba module BUILD SUCCESS. [Surefire](../load-test/results/reliability/reactor.txt) |
| Các chế độ khóa còn lại | PESSIMISTIC 14 đạt, OPTIMISTIC 14 đạt, REDIS 16 đạt (gồm hai nhánh commit/rollback). Chạy riêng NONE tái hiện bán vượt tồn dù stock cuối bằng 0. [PESSIMISTIC](../load-test/results/reliability/suite-PESSIMISTIC.txt) · [OPTIMISTIC](../load-test/results/reliability/suite-OPTIMISTIC.txt) · [REDIS](../load-test/results/reliability/suite-REDIS.txt) · [NONE](../load-test/results/reliability/suite-NONE.txt) |
| Cùng khóa và cùng payload | 32 POST thật qua Order trả một ID. Đối soát sau restore và phát lại khóa xác nhận chỉ trừ một lần. Khi hai payload khác nhau tranh cùng khóa, bài test HTTP trả đúng một 201 và một 409. [Dữ liệu 32 phản hồi](../load-test/results/reliability/restore.json) |
| Hai bản sao Inventory | Hai JVM độc lập nhận đồng thời 20/32 lệnh với stock 0, 1, 10. Mỗi reserve hoặc release thành công lần đầu tăng version đúng một, các lần lặp không tăng version. Cấm stock âm bằng CHECK của DB. Lỗi trigger lúc commit rollback cả hai thay đổi. |
| Inventory bị SIGKILL khi trả reserve | Trước khi kill đọc được PENDING/RESERVED bằng hai kết nối DB độc lập. Sau khi retry vẫn còn một RESERVED, đơn CONFIRMED. [Ảnh chụp](../load-test/results/reliability/faults.json) |
| Order bị SIGKILL khi đang đợi phản hồi | Client mất kết nối. Order khởi động lại với PENDING đã commit, đọc reservation rồi chuyển CONFIRMED. |
| Phản hồi reserve cũ và release bị mất | Chặn reserve, chờ bộ đối soát ghi ý định hủy và hoàn tất release, rồi cho reserve trả RESERVED cũ. Order trả 202. Kill Inventory trước khi gửi phản hồi release. Trạng thái cuối REJECTED/RELEASED. Tồn 150 ban đầu còn 52 với 98 RESERVED và 1 RELEASED, version 100. |
| Backlog và restart | Gửi 96 đơn khi cả hai Inventory dừng, tất cả trả 202. Kill Order, khởi động lại cả stack. Trong lượt đầu 96/96 đơn CONFIRMED, tuổi lớn nhất 20,89 giây, không còn đơn chờ. [Tuổi từng đơn](../load-test/results/reliability/faults.json) |
| Kiểm tra chính công cụ đối chiếu | Ba bài unittest bao gồm đối chứng âm. Một đơn lịch sử không cần reservation, mọi đơn mới đã xác nhận cần đúng orderId, productId và số lượng. [Kết quả](../load-test/results/reliability/verifier.txt) |

Lượt gây lỗi đầu dừng ở nhánh mất kết nối client vì tên hàm Python che khuất tên module. Đã sửa bộ kiểm thử và chạy lại từ DB trống. Kết quả lỗi của chính harness được giữ [tại đây](../load-test/results/reliability/faults-interrupted-client.json).

## Giới hạn

Các chứng cứ dùng cùng một máy Docker và mất tiến trình có chủ đích. Chưa thử sự cố hỏng ổ đĩa, failover PostgreSQL, lệch đồng hồ giữa máy hoặc khôi phục theo thời điểm khi writer vẫn hoạt động. Khi mất liên lạc lâu, số đơn chờ có thể tăng ngoài ngưỡng 90 giây. Bộ đối soát xử lý tuần tự từng lệnh trong batch; cần theo dõi cả tuổi đơn cũ nhất và tốc độ giảm backlog trong triển khai thực.

Kịch bản cắt phản hồi đặt read timeout của Order là 30 giây và cho phép một lần thử lại để điều khiển thứ tự sự kiện. Riêng cuộc đua hủy dùng tuổi bắt đầu đối soát 3,5 giây và hạn hủy 3 giây để lần đối soát đầu tiên chọn nhánh hủy. Lượt backlog dùng tuổi đối soát 1 giây, hạn hủy 60 giây và giữ giới hạn batch 32 như trong ứng dụng. Lượt restore chạy cấu hình thời gian mặc định. Các tham số và cổng chặn chỉ áp dụng cho Compose kiểm thử.

Ca khởi động từ đầu đã đặt thành công một reservation ATOMIC khi Redis ở trạng thái unhealthy, tồn từ 1 xuống 0. Khi tạm dừng Redis trong lúc mua qua Order, ATOMIC trả 201; chế độ REDIS trả 202, tồn giữ ở 5 và chưa có bản ghi giữ hàng. Sau khi Redis phục hồi, phát lại cùng khóa trả 200, tồn còn 3 và version bằng 1 ở cả hai cấu hình. [Cold start](../load-test/results/reliability/cold-start-after.json) · [Ảnh chụp hai DB khi mất Redis và sau phục hồi](../load-test/results/reliability/redis-recovery.json)

Lượt lặp giữ đủ 96 đơn qua nhiều chu kỳ đối soát khi Inventory dừng. Sau restart, số đơn chờ qua các mốc 96 → 84 → 64 → 52 → 32 → 16 → 0. Tuổi đơn khi hoàn tất có p95 26.15 giây, lớn nhất 27.87 giây so với ngưỡng mục tiêu 90 giây (đạt). Tồn cuối 52, version 100. [Chuỗi quan sát và tuổi 96 đơn](../load-test/results/reliability/faults-repeat.json)

Lượt restore cuối tạo đơn lịch sử bằng ảnh build từ tag `monolith-v1` tại `3ae96ef`. Hai dump trên đĩa khớp SHA-256. Tất cả bảng public và trạng thái sequence khớp trước khi mở lại dịch vụ. Đơn chờ hoàn tất ở tuổi 24.58 giây. Cả hai lượt restore kết thúc với cùng tồn 11 và không còn PENDING. [Kết quả từ tag](../load-test/results/reliability/restore-tag.json) · [Cặp dump tổng hợp và manifest](../load-test/results/reliability/restore-tag-backup/)
