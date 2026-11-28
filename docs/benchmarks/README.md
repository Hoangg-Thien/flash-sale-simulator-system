# Đo hiệu năng mua hàng

So sánh monolith tại tag `monolith-v1^{commit}=3ae96ef` với Order, hai bản sao Inventory và hai PostgreSQL. Kịch bản đo gọi API mua thật bằng HTTP trên mạng Docker, bao gồm chi phí REST giữa hai dịch vụ. Chỉ một kiến trúc nhận tải tại một thời điểm.

## Điều kiện đo

- Hai JVM monolith, mỗi JVM giới hạn 1,5 CPU / 768 MiB. Order và hai JVM Inventory, mỗi JVM 1 CPU / 512 MiB. Tổng ngân sách JVM mỗi kiến trúc là 3 CPU / 1.536 MiB.
- Monolith dùng PostgreSQL với 2 CPU / 1.024 MiB. Hai DB dịch vụ mỗi DB 1 CPU / 512 MiB. Mỗi kiến trúc dùng một Redis 0,5 CPU / 128 MiB. k6 chạy trên cùng host. Không có lớp cân bằng tải bổ sung.
- Trước mỗi chế độ, tạo lại container của **cả hai** kiến trúc. Làm nóng riêng mỗi kiến trúc 15 giây tại 100 VU. Trước **từng lượt**, đặt lại PostgreSQL, bộ sinh ID và Redis của kiến trúc đang đo. Kho có 32 SKU, mỗi SKU 100.000 đơn vị. Mỗi request có khóa riêng, mua một đơn vị, phân phối tuần tự đều theo SKU.
- Mỗi tổ hợp chạy ba lượt 10 giây ở 20/100/200 VU, dùng vòng kín constant-vus. Đảo thứ tự hai kiến trúc ở lượt thứ hai. Cho phép 45 giây để hoàn tất request cuối. Bộ sinh tải trên cùng máy, kết nối qua bridge có chi phí thực nhưng không mô phỏng WAN.
- Lưu mẫu k6 JSON nén, summary, snapshot SQL, kết quả từng lượt và thông số máy. Mỗi phản hồi được nối theo khóa gửi, ID trả về và trạng thái cuối trong DB. Đợi tối đa 90 giây cho backlog sau tải. Đối soát lỗi hoặc còn PENDING làm script dừng trước khi reset dữ liệu tiếp theo.
- HTTP/s đếm mọi phản hồi. Chấp nhận/s đếm HTTP 200, 201, 202 chia cho khoảng từ lúc gửi request đầu đến phản hồi cuối. Xác nhận/s dùng số đơn SUCCESS hoặc CONFIRMED đã commit, chia cho khoảng từ request đầu đến mốc muộn hơn giữa phản hồi cuối và commit cuối. Báo cáo HTTP ngoài 2xx gồm cả từ chối nghiệp vụ. Thống kê `http_req_duration` của k6 đo gửi, chờ và nhận, không gồm thiết lập kết nối. `purchase_wall_ms` ghi thêm thời gian nhìn từ client.
- Khoảng từ HTTP 202 đến completed_at dùng đồng hồ của k6 và PostgreSQL trên cùng máy, làm tròn về 0 nếu đơn đã kết thúc trước khi client nhận phản hồi. Không có 202 thì không có mẫu cho thống kê này. Độ trễ khôi phục sau sự cố được đo riêng trong [diễn tập phục hồi](../KIEM_THU_DO_TIN_CAY.md).
- Bảng dùng trung vị của ba tốc độ và trung vị của ba phân vị từng lượt. Khoảng min–max ở cột xác nhận/s cho biết độ dao động. Tỷ lệ lỗi và tổng số đơn dùng tổng của ba lượt. Đây là phép đo ngắn trên một máy, thứ tự VU tăng dần và JIT/cache có thể còn thay đổi. Kết quả không dự báo công suất ổn định dài hạn, độ trễ mạng liên vùng hoặc thông lượng dưới luồng đến cố định.

## Tái hiện

```bash
bash load-test/benchmark/prepare.sh
python3 -B load-test/benchmark/run.py /tmp/ket-qua-mua-hang
python3 load-test/benchmark/report.py /tmp/ket-qua-mua-hang
python3 load-test/benchmark/archive.py /tmp/ket-qua-mua-hang
```

Chạy từ gốc repo, dùng thư mục kết quả mới cho mỗi chiến dịch. Có thể tăng `--seconds` để đo dài hơn. Bản monolith được build từ `git archive` của tag. Hai dịch vụ được build từ mã nguồn đang làm việc. Lệnh chuẩn bị cần Maven, Java 21 và Docker BuildKit. Không chạy Maven, các diễn tập sự cố, hoặc tải khác đồng thời với benchmark. Sau khi đo, `archive.py` dùng Python 3.11 trở lên để tạo bản XZ cho từng lượt đo. Script giữ tệp gzip do k6 và công cụ đo tạo, rồi kiểm tra SHA-256 trên dữ liệu giải nén trước khi lưu bản XZ.

Lượt thăm dò ban đầu được giữ riêng vì JVM monolith đã được làm nóng lâu hơn sau khi đổi chế độ. [Ghi chú và số liệu tổng hợp của lượt thăm dò](../../load-test/results/purchase-pilot/README.md)

## Kết quả

| Chế độ | VU | Kiến trúc | HTTP/s | Chấp nhận/s | Xác nhận/s (min–max) | HTTP p50/p95/p99 (ms) | HTTP ngoài 2xx | Tổng xác nhận / yêu cầu |
|---|---:|---|---:|---:|---:|---:|---:|---:|
| ATOMIC | 20 | mono | 359.7 | 359.7 | 359.7 (239.9–405.0) | 31/166/181 | 0.00% | 10095 / 10095 |
| ATOMIC | 20 | rest | 116.0 | 116.0 | 116.0 (86.2–139.6) | 194/290/299 | 0.00% | 3443 / 3443 |
| ATOMIC | 100 | mono | 484.5 | 484.5 | 484.5 (435.3–544.7) | 86/701/984 | 0.00% | 14939 / 14939 |
| ATOMIC | 100 | rest | 170.7 | 170.7 | 170.7 (161.4–187.3) | 508/1189/1495 | 0.00% | 5374 / 5374 |
| ATOMIC | 200 | mono | 663.9 | 663.9 | 663.9 (589.4–759.6) | 34/1097/1503 | 0.00% | 20771 / 20771 |
| ATOMIC | 200 | rest | 212.2 | 212.2 | 212.2 (195.3–231.0) | 897/1896/2605 | 0.00% | 6706 / 6706 |
| PESSIMISTIC | 20 | mono | 442.6 | 442.6 | 442.6 (355.3–497.9) | 17/103/177 | 0.00% | 13015 / 13015 |
| PESSIMISTIC | 20 | rest | 126.2 | 126.2 | 126.2 (91.8–149.3) | 189/207/295 | 0.00% | 3705 / 3705 |
| PESSIMISTIC | 100 | mono | 617.2 | 617.2 | 617.2 (533.9–623.2) | 17/586/788 | 0.00% | 18036 / 18036 |
| PESSIMISTIC | 100 | rest | 173.8 | 173.8 | 173.8 (171.5–183.2) | 505/1105/1400 | 0.00% | 5432 / 5432 |
| PESSIMISTIC | 200 | mono | 775.5 | 775.5 | 775.5 (708.6–896.6) | 19/982/1320 | 0.00% | 24347 / 24347 |
| PESSIMISTIC | 200 | rest | 205.4 | 205.4 | 205.4 (179.6–233.6) | 894/2092/2702 | 0.00% | 6504 / 6504 |
| OPTIMISTIC | 20 | mono | 359.8 | 355.8 | 355.8 (234.5–401.5) | 23/170/184 | 1.38% | 9975 / 10115 |
| OPTIMISTIC | 20 | rest | 119.3 | 119.3 | 119.3 (90.2–147.4) | 192/289/302 | 0.00% | 3598 / 3598 |
| OPTIMISTIC | 100 | mono | 478.2 | 422.7 | 422.7 (375.9–456.2) | 64/705/988 | 11.49% | 12914 / 14591 |
| OPTIMISTIC | 100 | rest | 174.8 | 171.1 | 171.1 (161.0–177.3) | 508/1112/1400 | 2.00% | 5255 / 5362 |
| OPTIMISTIC | 200 | mono | 625.4 | 547.1 | 547.1 (498.5–625.4) | 72/1185/1592 | 12.31% | 17268 / 19691 |
| OPTIMISTIC | 200 | rest | 212.4 | 206.3 | 206.3 (190.7–221.2) | 821/1943/2596 | 3.00% | 6505 / 6706 |
| REDIS | 20 | mono | 306.5 | 306.5 | 306.5 (215.4–342.5) | 57/165/187 | 0.00% | 8702 / 8702 |
| REDIS | 20 | rest | 96.7 | 96.7 | 96.7 (80.0–126.1) | 201/299/384 | 0.00% | 3068 / 3068 |
| REDIS | 100 | mono | 338.6 | 337.8 | 337.8 (326.1–349.3) | 111/977/1511 | 0.18% | 10461 / 10480 |
| REDIS | 100 | rest | 151.0 | 151.0 | 151.0 (138.6–151.6) | 604/1294/1614 | 0.00% | 4589 / 4589 |
| REDIS | 200 | mono | 404.6 | 397.9 | 397.9 (382.5–444.4) | 153/1603/2102 | 1.74% | 12908 / 13136 |
| REDIS | 200 | rest | 183.5 | 183.5 | 183.5 (152.3–187.4) | 1005/2195/2893 | 0.00% | 5556 / 5556 |

| Kiến trúc | Mã HTTP, tổng 36 lượt | Trạng thái đã lưu | Số HTTP 202 | Từ 202 tới kết thúc p50/p95/max (ms) |
|---|---|---|---:|---|
| mono | 201=173431, 409=4487 | SUCCESS=173431 | 0 | không có mẫu |
| rest | 201=59735, 409=308 | CONFIRMED=59735, REJECTED=308 | 0 | không có mẫu |

Cả 72 lượt đo đều có số lượng bán vượt tồn bằng 0, chênh lệch `tồn đầu − lượng đã mua − tồn cuối` bằng 0 cho từng SKU và không còn PENDING. Trong 60.043 lần mua qua hai dịch vụ, DB ghi 59.735 CONFIRMED khớp RESERVED và 308 REJECTED khớp reservation đã từ chối. Monolith trả 4.487 phản hồi 409 và chỉ lưu đơn thành công. Cả hai hệ thống đều còn hàng. Ở chế độ OPTIMISTIC, yêu cầu tranh cùng phiên bản có thể bị từ chối ngay cả khi còn stock. Các phản hồi 409 của REDIS phía monolith cũng được tính vào tỷ lệ thất bại.

Monolith có trung vị số đơn xác nhận/giây cao hơn trong cả 12 tổ hợp. Hai dịch vụ dùng thêm một yêu cầu HTTP nội bộ và các lần commit riêng, đồng thời chia ngân sách CPU giữa nhiều tiến trình. Phép đo toàn hệ thống này không tách riêng chi phí của từng yếu tố. Không có 202 trong 72 lượt chuẩn, do đó thông lượng chấp nhận và thông lượng xác nhận trùng nhau; các ca gây lỗi riêng cho thấy chúng có thể khác nhau khi Inventory ngừng hoạt động.

[Bộ dữ liệu theo lượt](../../load-test/results/purchase-matrix/) · [Số đo và kiểm tra DB của cả chiến dịch](../../load-test/results/purchase-matrix/results.json) · [Hệ điều hành, CPU, RAM, Docker, JDK, k6 và image ID](../../load-test/results/purchase-matrix/environment.json) · [Dấu vân tay mã ứng dụng và tệp build](../../load-test/results/purchase-matrix/application.sha256)

`application.sha256` ghi checksum theo byte trên máy chạy benchmark. Khi checkout với LF, 43/65 tệp khớp trực tiếp. Với 22 tệp còn lại, đổi LF thành CRLF trong bộ nhớ trước khi tính SHA-256 sẽ cho đúng checksum đã ghi.

Trong mỗi thư mục `MODE-VU-LẦN-KIẾN_TRÚC`, `samples.json.xz` là từng mẫu k6, `summary.json` chứa các phân vị, `database.json.xz` chứa các dòng nghiệp vụ sau khi xả backlog và `result.json` chứa phép đối soát cùng số đo dẫn xuất. Git lưu đủ mẫu gốc và ảnh chụp SQL cho 72 lượt đo. Với 8 lượt làm nóng (lượt 0), Git lưu `summary.json` và `result.json`. Các bản gzip, dữ liệu thô của lượt làm nóng và log console do script tạo được giữ ở máy chạy. Thư mục thăm dò chỉ đưa ghi chú, thông số máy và kết quả tổng hợp vào Git. Ví dụ đọc mẫu gốc và tồn cuối cho lượt ATOMIC thứ nhất ở 200 VU:

```bash
cd load-test/results/purchase-matrix/ATOMIC-200-1-rest
xz -dc samples.json.xz | head -n 4
xz -dc database.json.xz | jq '.inventory'
jq '{http,confirmed_rps,latency_ms,verification,balance_delta}' result.json
```
