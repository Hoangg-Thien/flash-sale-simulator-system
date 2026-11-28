# ADR-003 · Giữ hàng qua REST và đối soát đơn đang chờ

- Trạng thái: Đã chấp nhận
- Phạm vi: Luồng mua hàng liên dịch vụ

## Bối cảnh

Lời gọi giữ hàng có thể hết thời gian chờ sau khi Inventory đã commit. Khi đó Order cần xác định kết quả mà vẫn cho phép tiến trình khởi động lại hoặc client gửi lại yêu cầu.

## Quyết định

Dùng REST đồng bộ để trả kết quả ngay khi có thể. Ghi đơn `PENDING` và giá mua trước khi gọi Inventory. Ràng buộc duy nhất trên `Idempotency-Key` để nhận diện yêu cầu mua của client. Dùng một `orderId` cho mọi lần gọi giữ hàng, tra cứu và hoàn kho.

Nếu hết retry mà chưa rõ kết quả, bộ đối soát đọc đơn đang chờ và tra cứu Inventory. Khi quyết định hủy, Order commit `cancel_requested=true` trước khi gọi release. Cập nhật xác nhận chỉ thành công khi đơn vẫn là `PENDING` và cờ hủy còn tắt. Nếu release đến trước reserve, Inventory ghi dấu hủy để ngăn giữ hàng đến muộn. [Vòng đời và điều kiện chuyển trạng thái](../VONG_DOI_DON_HANG.md)

## Hệ quả

Các giao dịch PostgreSQL đều kết thúc cục bộ. Mỗi lần gọi qua mạng có timeout và retry hữu hạn. Trong thời gian chưa xác minh được kết quả, client có thể nhận `202` và tra cứu lại bằng mã đơn. Bộ đối soát chạy tuần tự theo lô, vì vậy tuổi đơn phụ thuộc độ dài gián đoạn và số lượng đơn chờ. Bài kiểm thử đã gây mất phản hồi tại thời điểm commit và kiểm chứng khôi phục sau khi dừng tiến trình. [Ma trận sự cố](../KIEM_THU_DO_TIN_CAY.md)
