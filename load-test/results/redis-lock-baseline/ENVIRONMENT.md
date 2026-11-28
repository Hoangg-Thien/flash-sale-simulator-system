# Môi trường đo kết quả cơ sở cho khóa Redis — 2026-11-14 (Asia/Bangkok)

- Commit bắt đầu: `02f2d99d1cf0a64a1fa8083a488fe215549ff025`; nhánh `feature/hardening`, kèm bản sửa Giai đoạn 0 chưa được commit tại thời điểm đo. Không tạo commit hoặc đẩy mã lên kho từ xa trong quá trình thực hiện.
- Máy chủ chạy thử: Windows 11 x64, AMD Ryzen 5 7535HS, 6 nhân / 12 CPU logic, RAM 15.32 GiB. Docker Desktop dùng bộ máy Linux phiên bản 29.6.2: 12 CPU, RAM khả dụng 7,968,628,736 byte. VS Code và các ứng dụng khác vẫn chạy; số đo trên máy cá nhân không đại diện cho năng lực phục vụ của môi trường vận hành thực tế.
- Biên dịch và kiểm thử: Oracle JDK 21.0.2, Maven wrapper 3.9.16. Môi trường chạy ứng dụng: Temurin 21.0.13+9, `-XX:MaxRAMPercentage=75.0`.
- PostgreSQL 17.9; Redis 7.4.11; k6 2.2.0 (`00a9a1b7f5`, Go 1.26.5, chạy trực tiếp trên Windows amd64). Kiểm thử tích hợp dùng cấu hình Testcontainers PostgreSQL 16 / Redis 7 có sẵn.
- Một phiên ứng dụng tại `http://127.0.0.1:18080`. Tên dự án Compose dùng khi chạy lại là `flashsale-redis-baseline`, không dùng cơ sở dữ liệu cục bộ hoặc vùng lưu trữ Compose có sẵn. Xem [compose.yml](compose.yml).
- Giới hạn tài nguyên: ứng dụng 2 CPU / 1 GiB; PostgreSQL 2 CPU / 1 GiB; Redis 1 CPU / 256 MiB. k6 chạy trên máy chủ, không giới hạn CPU/bộ nhớ. Tắt việc in câu lệnh SQL; mức ghi nhật ký gốc và SQL của Hibernate đều là WARN trong mọi lượt đo.
- Kịch bản: `load-test/comparison.js` được giữ nguyên tại thời điểm đo, mỗi chế độ dùng một sản phẩm, tồn kho ban đầu **5**, số lượng mua **1**, tối đa **20 người dùng ảo (VUs)**. Mỗi chế độ tăng tải 3 giây, giữ tải 15 giây, giảm tải 3 giây; bắt đầu ở giây 0/25/50/75, tổng thời gian dự kiến 96 giây. Các chế độ: NONE, OPTIMISTIC, PESSIMISTIC, REDIS.
- Chạy một lượt so sánh đầy đủ để làm nóng, sau đó đo ba lượt so sánh liên tiếp. Xóa dữ liệu các bảng dùng để đo, đặt lại bộ đếm định danh và xóa dữ liệu Redis riêng giữa các lượt đo. JVM tiếp tục chạy. Không chạy kiểm thử tích hợp đồng thời với quá trình đo.
- Dữ liệu sự kiện gốc được lưu dưới dạng JSON Lines nén gzip (`comparison-N.json.gz`), kèm JSON tổng hợp của k6, đầu ra dòng lệnh và số đếm tồn kho/đơn hàng độc lập từ cơ sở dữ liệu cho từng lượt. Git nhận diện các kết quả trong thư mục con mà không cần đổi quy tắc bỏ qua có sẵn. Các kết quả từ tháng 9 vốn bị Git bỏ qua được giữ nguyên.
- SHA-256 của tệp JAR đã kiểm thử: `8B29195AF781256A281358B942C7CBC31A0909457948D2934DB6B2063890EDD1`.

Mã định danh nội dung các ảnh container đã dùng:

```text
eclipse-temurin@sha256:ac05c3c42b7c2f1a52c9bd52f01041fa1958231b15ebeae7420429cbc5baf474
postgres@sha256:b994732fcf33f73776c65d3a5bf1f80c00120ba5007e8ab90307b1a743c1fc16
redis@sha256:ff02b58f971e7d7d156a1267e283fcbbeee91773b6aa36c49dac28ecfe28eadf
```

## Cách chạy lại

Thực hiện tại thư mục gốc của kho mã, sau khi cài k6 và khởi động Docker Desktop:

```powershell
.\mvnw.cmd clean package
docker compose -p flashsale-redis-baseline -f load-test/results/redis-lock-baseline/compose.yml up -d
# Chờ GET http://127.0.0.1:18080/actuator/health trả về UP.
k6 run --quiet --env BASE_URL=http://127.0.0.1:18080 load-test/comparison.js
powershell -NoProfile -File load-test/results/redis-lock-baseline/run-baseline.ps1
docker compose -p flashsale-redis-baseline -f load-test/results/redis-lock-baseline/compose.yml down -v
```

Sao lưu đầu ra Giai đoạn 0 trước khi chạy lại: các kịch bản ghi đè tệp kết quả; riêng `run-status.txt` được ghi nối tiếp. Cố định ảnh container theo các mã định danh ở trên để tái tạo đúng phiên bản môi trường đã dùng.

## Giới hạn khi diễn giải kết quả

Kịch bản so sánh ban đầu chỉ xác định việc bán vượt tồn kho thông qua giá trị tồn kho âm. Lỗi ghi đè làm mất cập nhật trong NONE có thể tạo quá nhiều đơn trong khi tồn kho vẫn không âm. Các tệp `*-stock.csv` đếm độc lập số đơn thành công đã được ghi nhận trong cơ sở dữ liệu; các chế độ an toàn phải thỏa `confirmed + final_stock = 5` và `final_stock >= 0`.

Năm đơn vị tồn kho được bán hết gần đầu mỗi chế độ, nên phần lớn yêu cầu đo việc từ chối mua khi hết hàng. Xung đột OPTIMISTIC có thể khiến yêu cầu bị từ chối trong khi vẫn còn hàng; việc tiếp tục gửi yêu cầu trong kịch bản này có thể che khuất hiện tượng đó. Đây là kết quả cơ sở của kịch bản có sẵn theo yêu cầu, chưa phải ma trận đo của Giai đoạn 5 hoặc bằng chứng về tính đúng đắn khi chạy nhiều phiên ứng dụng. Hàm phân loại phản hồi của kịch bản cũng coi các phản hồi HTTP 200 khi chuẩn bị/đặt lại/dọn dẹp là ngoài dự kiến; các yêu cầu này vẫn xuất hiện trong tỷ lệ lỗi có sẵn của k6 dù phản hồi mua hàng đúng.
