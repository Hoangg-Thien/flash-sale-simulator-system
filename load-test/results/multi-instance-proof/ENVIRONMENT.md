# Môi trường kiểm chứng monolith nhiều instance

- Ngày đo: **2026-11-15**, múi giờ Asia/Bangkok. Nhánh `feature/hardening`, SHA bắt đầu `3c1dc15`; tại thời điểm đo, các thay đổi Giai đoạn 2 chưa commit hoặc đẩy lên kho từ xa.
- Máy cá nhân: Windows 11 x64, AMD Ryzen 5 7535HS, 6 nhân / 12 CPU logic, RAM 15.32 GiB. Các ứng dụng và IDE khác vẫn chạy.
- Docker Desktop Linux engine **29.6.2**, Compose **5.3.1**, 12 CPU logic và RAM khả dụng **7,968,632,832 byte**. Tệp ghi đè dùng `!reset`/`!override`, cần Compose >= 2.24.4.
- Biên dịch/kiểm thử trên máy: Oracle JDK 21.0.2, Maven wrapper 3.9.16, Spring Boot 3.5.13. Image ứng dụng build bằng Dockerfile hiện tại, dùng Maven 3.9.9/Java 21 ở bước biên dịch và Temurin **21.0.13+9** ở bước chạy.
- PostgreSQL khi đo: **17.9**; Redis **7.4.11**. Các kiểm thử tích hợp dùng PostgreSQL 16 và Redis 7 qua Testcontainers, hai JVM con có heap tối đa 256 MiB mỗi JVM.
- k6 **2.2.0** (`00a9a1b7f5`, Go 1.26.5, Windows amd64), chạy trực tiếp trên máy chủ, không giới hạn CPU/RAM riêng.
- Hai container ứng dụng độc lập cùng image `sha256:2005caebc99ca3ecb87c8522cbc559cd348718dd5371c3d2fdc7bca678e26406`. `app-a` và `app-b` cùng dùng PostgreSQL/Redis, không có Gateway hoặc dịch vụ mới.
- Giới hạn: **mỗi app 2 CPU / 1 GiB**, PostgreSQL 2 CPU / 1 GiB, Redis 1 CPU / 256 MiB. JVM trong container dùng `-XX:MaxRAMPercentage=75.0`; tắt việc in SQL và đặt mức log SQL Hibernate là WARN, giữ log HTTP có requestId/instanceId để đối chiếu traffic.
- Project đo riêng: `flashsale-multi-proof`; cổng HTTP **18080/18081**, PostgreSQL/Redis không công khai cổng. Root Compose vẫn dùng 8080/8081 cho cách chạy thông thường. Không dùng hoặc đặt lại dữ liệu của project cục bộ khác.
- SHA-256 JAR sau `clean package` trên máy: `C00DD5D0B3FAAD3B23FC4286001C2AEB4C50850736631A10FD359D191CFED16E`.
- SHA-256 JAR thực tế trong image đã đo: `759ea7b1abe0943b7f67e5c0b9d08024186f88aae024ab840b06870bbba7db72`. Hai tệp được build riêng trên máy và trong Docker; không giả định chúng giống nhau về byte.

## Cấu hình phép đo

Kịch bản `load-test/multi-instance.js` dùng `per-vu-iterations`: **20 người dùng ảo, mỗi người gửi đúng 1 yêu cầu**, tối đa 30 giây cho lượt. VU luân phiên hai URL, mỗi instance nhận 10 yêu cầu mua. Header trả về phải nhận diện đúng instance và giữ nguyên requestId; mã HTTP mua phải là 201 hoặc 409. Các trường hợp 409 phải có requestId trong nội dung lỗi.

Mỗi mode có một lượt làm nóng (round 0) và ba lượt đo (round 1–3), chạy lần lượt với cùng cấu hình:

| Nhóm | Mode | Stock đầu | Số lượng mỗi yêu cầu | Người mua |
|---|---|---:|---:|---:|
| An toàn | ATOMIC, OPTIMISTIC, PESSIMISTIC, REDIS | 10 | 1 | 20 |
| Trình diễn riêng | NONE | 1 | 1 | 20 |

Mỗi lượt tạo sản phẩm mới với mã ngẫu nhiên, không reset sản phẩm của lượt trước. NONE chỉ được bật rõ ràng bằng `-IncludeUnsafe`; nó không dùng chung sản phẩm với các mode an toàn. Thư mục `low-demand/` lưu phép kiểm tra bổ sung: một lượt làm nóng và một lượt đo cho mỗi mode an toàn, **2 người mua, stock 10**.

Sau mỗi lượt, runner truy vấn trực tiếp PostgreSQL để lưu số đơn, số khóa idempotency riêng biệt, tổng số lượng đã mua, số đơn được phân theo node và stock cuối. Đồng thời đối chiếu stock qua cả hai API và số dòng log HTTP của từng container với số request k6. Các điều kiện được kiểm tra trước khi ghi nhận lượt đo đạt; lỗi làm runner dừng với tài nguyên riêng vẫn được dọn trong `finally`.

RPS và phân vị HTTP trong `comparison.csv`/JSON tổng hợp **bao gồm cả chuẩn bị và đọc stock cuối**, không chỉ POST mua hàng. Đây là phép thử đồng thời ngắn để kiểm chứng dữ liệu, chưa phải thử tải kéo dài hoặc số đo năng lực vận hành thực tế. Không so trực tiếp với các kết quả một instance của Giai đoạn 0–1.

## Chạy lại

Cài JDK 21, Docker Desktop, k6 và PowerShell. Thực hiện từ thư mục gốc; sao lưu thư mục kết quả trước vì tệp có thể bị ghi đè:

```powershell
.\mvnw.cmd clean package
powershell -NoProfile -File load-test/test-multi-instance-verification.ps1
powershell -NoProfile -File load-test/run-multi-instance.ps1 -IncludeUnsafe

# Phép kiểm tra số yêu cầu ít hơn lượng hàng:
powershell -NoProfile -File load-test/run-multi-instance.ps1 -Rounds 1 -Users 2 -Results load-test/results/multi-instance-proof/low-demand
```

Trên Linux/macOS, dùng `pwsh` thay `powershell`. Cách này đã được cấu hình trong workflow thủ công nhưng **chưa chạy từ xa trên GitHub hoặc trên Linux** trong lần kiểm chứng này.

Có thể chạy riêng các kiểm thử JVM:

```powershell
.\mvnw.cmd '-Dtest=MultiInstanceIntegrationTest,RequestCorrelationIntegrationTest' test
```

Runner tự dùng hai tệp Compose, build image, chờ HTTP health và dọn container/mạng/volume của project riêng khi kết thúc. Nếu bị tắt cưỡng bức trước khi `finally` chạy, kiểm tra và dọn **đúng project đo**:

```powershell
docker compose -p flashsale-multi-proof -f docker-compose.yml -f load-test/multi-instance.compose.yml down -v
```

## Bằng chứng và nguồn tham khảo

- `round-N-MODE.json.gz`: sự kiện k6 gốc, nén không mất dữ liệu; tệp `*-summary.json`, `*.txt`, `*-stock.csv` tương ứng lưu tổng hợp, đầu ra dòng lệnh và dữ liệu DB độc lập.
- `comparison.csv`: bảng mọi lượt, gồm cả làm nóng; chỉ các dòng `round > 0` là số đo báo cáo.
- `requests-app-a.txt`, `requests-app-b.txt`: log để đối chiếu requestId và số request thực nhận. `containers.txt`, `compose-build.txt`, `cleanup.txt` ghi image/tài nguyên, quá trình dựng và dọn hệ thống.
- [VERIFICATION.md](VERIFICATION.md) ghi kết quả và giới hạn; `SHA256SUMS.txt` dùng kiểm tra tính toàn vẹn dữ liệu. Các tệp đo có thuộc tính Git giữ nguyên byte khi checkout.
- [k6: mỗi VU thực thi số lượt cố định](https://grafana.com/docs/k6/latest/using-k6/scenarios/executors/per-vu-iterations/).
- [Docker Compose: ghép và ghi đè cấu hình](https://docs.docker.com/reference/compose-file/merge/).
