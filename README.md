# Hệ thống đặt hàng flash sale

Order Service quản lý danh mục, đơn hàng và giá mua. Inventory Service quản lý số lượng khả dụng và các lượt giữ hàng. Hai ứng dụng Java 21 dùng Spring Boot, Flyway và hai cơ sở dữ liệu PostgreSQL riêng. Hai bản sao Inventory dùng chung kho và chạy ở chế độ ATOMIC mặc định.

## Chạy và kiểm chứng

Chạy từ thư mục gốc với Docker BuildKit, Docker Compose từ 2.20.3 và Python 3. Dùng project riêng cho kiểm thử phục hồi vì chương trình sẽ dừng cả hai bản sao Inventory và khởi động lại Order.

```bash
mvn clean verify
export COMPOSE_PROJECT_NAME=flash-sale-dist
docker compose up --build -d --wait
python3 scripts/smoke-orders.py --recovery
docker compose down
```

Order mở cổng 8080 trên máy cục bộ. Có thể đặt `ORDER_PORT` trước khi khởi động. Script gửi yêu cầu mua, gửi lại khóa, thử mua khi thiếu hàng và đối chiếu trạng thái đã lưu trong cả hai cơ sở dữ liệu. Tùy chọn `--recovery` kiểm tra thêm đơn đang chờ qua một lần gián đoạn dịch vụ. Để chạy mô phỏng, bật `ORDER_PROFILES=docker,lab`.

## API và vận hành

- `POST /api/v1/orders` nhận `productId`, `quantity` và header `Idempotency-Key`. Phản hồi gồm `201` khi tạo đơn đã xác nhận, `200` khi đọc lại đơn đã xác nhận, `202` khi đang chờ và `409` khi từ chối hoặc xung đột dữ liệu. `GET /api/v1/orders/{id}` trả trạng thái mới nhất. [Vòng đời đơn hàng](docs/VONG_DOI_DON_HANG.md)
- Khởi tạo và chuyển dữ liệu tồn kho cần được xác nhận trước khi mở bán sản phẩm. [Chuyển dữ liệu tồn kho](docs/CHUYEN_DU_LIEU_TON_KHO.md)
- Các API giữ hàng, hoàn kho và đọc số lượng dùng tiền tố nội bộ `/internal/v1`. [Dịch vụ tồn kho](docs/INVENTORY_SERVICE.md)
- Để tái hiện các phép đo ứng dụng đơn khối, dùng mã nguồn tại thẻ `monolith-v1`. [Kết quả kiểm thử đồng thời](load-test/results/multi-instance-proof/)
