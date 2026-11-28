# Kiểm chứng ranh giới khóa Redis và giao dịch

SHA bắt đầu: `02f2d99d1cf0a64a1fa8083a488fe215549ff025`. Công việc thực hiện trên nhánh `feature/hardening`; theo yêu cầu, các thay đổi được giữ ở trạng thái chưa commit và chưa đẩy lên kho từ xa tại thời điểm kiểm chứng. Giai đoạn 1 chưa bắt đầu tại thời điểm lập báo cáo này.

## Các thay đổi

- `InventoryLockStrategy`: phương thức mặc định `around(productId, Supplier<T>)` thực thi trực tiếp công việc được truyền vào đối với các chiến lược dùng cơ sở dữ liệu.
- `RedisLockStrategy`: chuyển phần lấy/nhả khóa có sẵn vào `around`, giữ nguyên khóa định danh, thời gian chờ, cách gọi bật cơ chế tự gia hạn khóa, ánh xạ ngoại lệ và điều kiện kiểm tra quyền sở hữu khóa. `deductStock` giữ nguyên việc kiểm tra/cập nhật tồn kho.
- `OrderService`: bọc giao dịch hiện có bằng `strategy.around(...)`; việc tạo đơn và xử lý lỗi được giữ nguyên.
- `LockStrategyUnitTest`: các kiểm thử Redis có sẵn gọi phần bao ngoài thao tác trừ kho; giữ nguyên các điều kiện kiểm tra.
- `RedisLockTransactionIntegrationTest`: ghi nhận thao tác nhả khóa Redis thật và các hàm được Spring gọi khi giao dịch hoàn tất, sử dụng PostgreSQL/Redis qua Testcontainers. Bao phủ trường hợp thành công, lỗi sau khi trừ kho và lỗi được chủ động tạo tại `beforeCommit`. Kiểm tra việc lấy khóa diễn ra ngoài giao dịch, trừ kho diễn ra trong giao dịch, giao dịch hoàn tất trước khi nhả khóa, số tồn kho/đơn đã lưu và trạng thái nhả khóa.
- Thư mục này chứa bằng chứng kiểm thử, đầu ra k6 gốc, thông số môi trường và lệnh chạy lại. Thay đổi Compose có sẵn ở thư mục gốc, kế hoạch chuyển đổi, tài liệu không liên quan và tệp PDF được giữ nguyên.

## Bằng chứng kiểm thử

1. **Trước khi sửa (RED):** kiểm thử tích hợp mới với ba trường hợp đã biên dịch và thực thi; có 3 điều kiện kiểm tra không đạt, không có lỗi thực thi. Thứ tự thực tế là `LOCKED, UNLOCKED, COMMITTED/ROLLED_BACK`. Xem [ordering-red.txt](ordering-red.txt). Lỗi thiếu khai báo import ban đầu đã được sửa trước lượt chạy RED thực sự này.
2. **Sau khi sửa (GREEN):** `./mvnw.cmd test`: phát hiện 39 trường hợp, 38 đạt, 1 bỏ qua, không có điều kiện kiểm tra thất bại hoặc lỗi thực thi.
3. **Độ ổn định:** Case 6b đạt **30/30**; Redis STABILITY đạt **30/30**, mỗi lần gọi vẫn có 3 mô phỏng như trước (90 mô phỏng). Tổng cộng **120 mô phỏng**. Xem [stability.txt](stability.txt). [run-stability.ps1](run-stability.ps1) chỉ tạm thay hai chú thích `@Test` được chọn bằng `@RepeatedTest(30)` và khôi phục nguyên byte gốc trong `finally`. Đã kiểm tra cả hai tệp kiểm thử được khôi phục nguyên vẹn sau khi chạy.
4. **Biên dịch sạch lần cuối:** `./mvnw.cmd clean package`: phát hiện 39 trường hợp, **38 đạt**, **1 bỏ qua**, không có điều kiện kiểm tra thất bại hoặc lỗi thực thi; kết quả `BUILD SUCCESS`. Xem [suite.txt](suite.txt). `FlashSaleApplicationTests.contextLoads` đã có `@Disabled` từ trước; thay đổi này không thêm trường hợp bỏ qua. Các cảnh báo có sẵn về `@SpyBean` bị đánh dấu ngừng dùng và cơ chế tác nhân Mockito/JDK vẫn còn.
5. Việc rà soát độc lập chỉ đọc không phát hiện vấn đề mức nghiêm trọng hoặc quan trọng trong bản sửa hay kiểm thử hồi quy.

Chạy kịch bản kiểm tra độ ổn định từ thư mục gốc của kho mã trước khi chạy lại toàn bộ bộ kiểm thử sạch; kịch bản giữ nguyên mã nguồn kiểm thử ban đầu. Nhật ký thực thi chi tiết trên máy gồm `phase0-red.log`, `phase0-stability.log` và `phase0-final-suite.log` (được Git bỏ qua).

## Đối chiếu kế hoạch và phạm vi

- Kế hoạch chuyển đổi thực tế nằm trong `docs/`; lỗi Redis được mô tả khớp với mã nguồn.
- Nhận định trong bản chụp rằng chưa có tệp kết quả cơ sở đã cũ so với dữ liệu trên máy: kết quả gốc từ tháng 9 vẫn tồn tại nhưng bị Git bỏ qua. Các tệp đó được giữ nguyên; thư mục này lưu kết quả cơ sở mới sau bản sửa.
- Cụm “ba kiểm thử Redis STABILITY” trong kế hoạch thực tế là một kiểm thử có ba vòng lặp bên trong. Toàn bộ ba vòng đều được lặp lại 30 lần.
- JSON gốc được lưu không mất dữ liệu dưới dạng JSON Lines nén gzip để giảm dung lượng bằng chứng. Git nhận diện thư mục con này mà không cần thay đổi `.gitignore`.
- Kế hoạch yêu cầu commit bằng chứng. Bước đó được để lại cho người dùng theo yêu cầu rõ ràng không tự commit hoặc đẩy mã lên kho từ xa.

## Kết quả đo cơ sở bằng k6

Chạy một lượt làm nóng đầy đủ, sau đó đo ba lượt với kịch bản so sánh được giữ nguyên. Cả ba lượt đều trả mã thoát **0**, đạt ngưỡng tỷ lệ lỗi HTTP `<1%` và không có phép kiểm tra nào thất bại. Số liệu tổng hợp bao gồm mọi chế độ cùng các yêu cầu chuẩn bị/dọn dẹp; không dùng để xếp hạng từng chiến lược khóa.

| Lượt | Số yêu cầu HTTP | Yêu cầu HTTP/giây | p50 ms | p95 ms | p99 ms | Tỷ lệ lỗi HTTP do k6 ghi nhận (%) |
|---|---:|---:|---:|---:|---:|---:|
| 1 | 68321 | 711.07 | 7.83 | 77.21 | 106.09 | 0.0117 |
| 2 | 91852 | 956.10 | 5.67 | 66.69 | 82.86 | 0.0087 |
| 3 | 91313 | 950.52 | 6.00 | 66.53 | 88.98 | 0.0088 |

Mỗi lượt ghi nhận tổng cộng **20 đơn thành công** trong cơ sở dữ liệu (5 đơn mỗi chế độ); tồn kho cuối của mọi sản phẩm đều là **0**. Số đếm độc lập từ PostgreSQL trong `comparison-N-stock.csv` thỏa `confirmed + remaining = 5` cho mọi chế độ an toàn. Tám yêu cầu bị k6 ghi nhận là lỗi HTTP trong mỗi lượt thực chất là phản hồi HTTP 200 thành công khi đặt lại/dọn dẹp, bị hàm phân loại có sẵn xem là ngoài dự kiến; mọi phép kiểm tra mua hàng đều đạt. Báo cáo giữ lại sự biến động số yêu cầu mỗi giây, không chỉ chọn lượt tốt nhất.

Dữ liệu gốc: [lượt 1](comparison-1.json.gz), [lượt 2](comparison-2.json.gz), [lượt 3](comparison-3.json.gz). Sau khi đo, từng tệp gzip đều được đọc thành công đến cuối. Các tệp `*-summary.json` chứa số liệu tổng hợp k6 tương ứng, `comparison-N.txt` chứa đầu ra dòng lệnh và [run-status.txt](run-status.txt) lưu mã thoát. [ENVIRONMENT.md](ENVIRONMENT.md) ghi thông số môi trường và lệnh tái chạy. Các container, mạng và vùng lưu trữ riêng phục vụ phép đo đã được xóa sau khi thu thập.

NONE không bán vượt tồn kho trong các lượt k6 này: tải tăng dần và lượng hàng chỉ có 5 có thể khiến hàng bán hết trước khi xuất hiện tranh chấp đáng kể. Kịch bản này không chứng minh NONE đúng; các kiểm thử mô phỏng cho người mua bắt đầu đồng thời vẫn kiểm tra lỗi mất cập nhật được cố ý giữ lại.

Toàn bộ công việc triển khai và kiểm chứng Giai đoạn 0 đã hoàn tất. Việc commit các tệp được để lại cho người dùng; báo cáo này không bao gồm thay đổi Giai đoạn 1.

## Giới hạn còn lại

- Việc bảo vệ ranh giới giả định công việc được truyền vào hoàn tất giao dịch mà nó sở hữu. Các nơi gọi hiện tại không có giao dịch bao ngoài; nếu sau này có nơi gọi với giao dịch REQUIRED bên ngoài thì cần thiết kế/kiểm thử riêng trước khi dựa vào cơ chế bao này.
- Lỗi commit được chủ động tạo tại `beforeCommit`, không phải trong lúc cơ sở dữ liệu thực sự commit hoặc mạng bị lỗi. Các kiểm thử có sẵn về hoàn tác giao dịch và Redis không khả dụng cũng đạt; lỗi Redis sau commit hoặc sự không chắc chắn về phản hồi sau commit nằm ngoài bản sửa này.
- Thời gian chờ khóa hai giây vẫn có thể hết hạn khi tranh chấp đủ lớn; bản sửa không thêm việc thử lại hoặc thay đổi API công khai.
- Giới hạn của kịch bản k6 và cấu hình môi trường cụ thể được ghi trong [ENVIRONMENT.md](ENVIRONMENT.md). Báo cáo không bao gồm bằng chứng chạy nhiều phiên ứng dụng, chế độ ATOMIC, việc tách dịch vụ hoặc ma trận đo của Giai đoạn 5.
