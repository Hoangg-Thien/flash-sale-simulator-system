# ADR-002 · Ranh giới Order và Inventory

- Trạng thái: Đã chấp nhận
- Phạm vi: Kiến trúc hai dịch vụ

## Bối cảnh

Mô hình đơn khối đã bảo toàn tồn kho trên nhiều tiến trình dùng chung PostgreSQL. Việc tách dịch vụ nhằm kiểm chứng quyền sở hữu dữ liệu và hành vi khi lời gọi qua mạng gặp lỗi. Danh mục sản phẩm và đơn hàng vẫn liên quan chặt chẽ vì giá mua phải được lưu cùng dòng hàng.

## Quyết định

Giữ danh mục, đơn hàng và giá tại thời điểm mua trong `order_db`. Inventory sở hữu tồn khả dụng và các bản ghi giữ hàng trong `inventory_db`. Order truyền mã sản phẩm và mã đơn qua REST. Các mã này liên kết dữ liệu nghiệp vụ mà không tạo khóa ngoại liên cơ sở dữ liệu. Mỗi dịch vụ có Flyway migration riêng.

Trong môi trường Compose, Order là điểm vào cho client và chọn luân phiên hai địa chỉ Inventory. Hai bản sao chia sẻ cùng dữ liệu Inventory. Giữ danh mục cùng Order giúp chụp giá trong một giao dịch cục bộ. Kết nối trực tiếp giữa hai dịch vụ đáp ứng phạm vi triển khai hiện tại. [Kiến trúc Compose](../../docker-compose.yml)

## Hệ quả

Mỗi DB có một dịch vụ chịu trách nhiệm ghi. Khi chuyển từ monolith, cần ngừng ghi và chuyển tồn còn lại, vì lượng hàng đã bán nằm trong số dư được chuyển. Sao lưu và phục hồi phải xét hai DB như một cặp. Chi phí của ranh giới mới thể hiện trong phép đo hệ thống trên cùng máy. [Chuyển dữ liệu](../CHUYEN_DU_LIEU_TON_KHO.md) · [Benchmark](../benchmarks/README.md)
