# Kiểm tra Aurelia NOVA V13.2 và Aurelia Live

## Dịch sát nghĩa và theo ngữ cảnh

Mình chép nguyên văn những câu tiếng Anh đọc được từ ảnh truyện rồi gọi API V13.2 với Client Key VIP. Bộ điều khiển, prompt, kiểm tra JSON, kiểm tra chất lượng và cache đều là của V13.2 gốc. Máy thử chạy trọng số `qwen3.5:4b` từ kho Ollama qua llama.cpp trên CPU. Phép đo này đi từ API đến câu dịch. Bước OCR của ảnh, mạng điện thoại và lớp phủ Chrome cần được đo riêng.

| Câu gốc | Bản NOVA | Nhận xét |
| --- | --- | --- |
| I've never seen sis get embarrassed before! | Trước đây chưa bao giờ thấy chị bị xấu hổ thế này! | Giữ quan hệ chị em và ý “chưa bao giờ”. “Em chưa thấy chị ngượng bao giờ!” hợp giọng trêu hơn. |
| Kang Suhyeon! Shut up! | Kang Suhyeon! Dừng lại! | Giữ đúng tên nhưng làm nhẹ mệnh lệnh. “Kang Suhyeon! Im đi!” sát thoại hơn. |
| It's no big deal! | Không phải chuyện lớn đâu! | Đúng ý. “Có gì đâu!” gọn hơn trong bong bóng. |
| Call me anytime if you're bored. | Nếu buồn thì gọi mình bất cứ lúc nào. | “Buồn” có thể bị đọc thành “buồn bã”. “Khi nào chán thì cứ gọi nhé!” sát hơn khi chưa chắc người nói. |
| You might be the “key” to lifting her curse. | Có lẽ bạn chính là 'chìa khóa' để giải trừ lời nguyền của cô. | Giữ sắc thái phỏng đoán và ẩn dụ. Xưng “cậu” hay “bạn” tùy quan hệ. |
| Looks like I won again today, Park Jin-yong~ | Có vẻ như tớ thắng ngày hôm nay, Park Jin-yong~ | Giữ tên, sót “again”. “Có vẻ hôm nay tớ lại thắng rồi, Park Jin-yong~” giữ đầy đủ ý. |

NOVA dịch hiểu được và giữ khá nhiều thông tin, nhưng cần soát xưng hô, biệt danh, từ chỉ mức độ và giọng thoại. Trong mẫu chị em, NOVA nhận ra vai người em và người chị khi có mô tả ngữ cảnh, nhưng vẫn gán người nói cho một bong bóng chưa xác nhận. Hai biệt danh “Glasses Oppa” và “Monster Oppa” được giữ nguyên tiếng Anh. Có thể thống nhất cách gọi theo bộ truyện bằng từ vựng và quy tắc xưng hô đã có ở server. Bộ kiểm tra tự động của NOVA không đánh dấu các lỗi sắc thái trên.

## Thời gian API trên máy thử

| Mẫu | Dịch mới | Cùng yêu cầu đã có cache |
| --- | ---: | ---: |
| Chị em, bốn bong bóng, có mô tả ngữ cảnh | 135,571 giây | 24 ms |
| Lời nguyền, hai bong bóng | 70,778 giây | 12 ms |
| Biệt danh, năm bong bóng | 77,194 giây | 13 ms |
| Chị em không có mô tả và mẫu sang chấn | Cả hai chạm giới hạn chờ 180 giây | — |

Các lượt có kết quả ở trên dùng cùng máy thử CPU. Kết quả cache là thời gian gọi API nội bộ với cùng yêu cầu. Kết quả này chưa xác nhận mốc dưới 5 giây cho bản NOVA mới. Trên Android, mốc dưới 5 giây là mục tiêu dành cho bản dịch nhanh của vùng đang hiển thị. App đặt giới hạn xử lý 4,8 giây và giữ những vùng đã dịch xong nếu còn thiếu. App có thể cập nhật sang bản NOVA sau đó khi vùng đọc vẫn còn hiển thị.

## Tính toàn vẹn và build

- 42 bài kiểm thử của server gốc đã qua.
- Android build debug và release thành công, lint không có lỗi, và hai kiểm thử hợp đồng dữ liệu NOVA đã qua. Kiểm thử xác nhận việc ghép theo `block_id` và loại bỏ phản hồi thiếu, trùng hoặc sai kiểu dữ liệu.
- Cả hai APK ARM đã được kiểm tra chữ ký. Nội dung và đường dẫn của 124 tệp gốc được so khớp riêng với ZIP đã phục hồi.
- Giả lập Android 15 bằng CPU khởi động lại framework trước khi cài xong APK, nên luồng OCR và lớp phủ chưa được xác nhận từ đầu đến cuối. Mốc 5 giây cần đo trên điện thoại thật.
