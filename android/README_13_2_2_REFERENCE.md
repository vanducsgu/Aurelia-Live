> **NOVA 13.2.2:** Thư mục `android/` có mã nguồn Aurelia Live **0.2.1-sync**.
> **APK đóng gói sẵn trong `android/cai-dat/` vẫn là bản 0.2.0**, chưa có các nút đồng bộ mới.
> Môi trường đóng gói không có Android SDK/Gradle distribution, nên chưa build/ký APK mới.
> Để dùng bản Android mới, hãy build từ mã nguồn bằng Android Studio/JDK 17/SDK 35.
> APK debug có thể có chữ ký khác bản 0.2.0; không cài đè được nếu khác chữ ký.

# Aurelia Live cho Android

Aurelia Live 0.2.0 chạy trên Android 8.0 trở lên. App đọc chữ trong hình đang hiển thị, dịch sang tiếng Việt và đặt bản dịch lên từng vùng chữ. Bạn vẫn có thể chạm và cuộn trong Chrome. App tự dịch tiếp khi khung hình ổn định sau khi cuộn.

## Cài và dùng

1. Cài APK trong `cai-dat/`. Chọn `arm64` cho đa số máy Android hiện nay. Dùng `armv7` nếu máy chỉ hỗ trợ ứng dụng ARM 32-bit.
2. Mở **Aurelia Live**. Chọn **Tiếng Anh** cho ảnh truyện trong lần thử này. App cũng hỗ trợ nguồn tiếng Nhật, Trung và Hàn.
3. Bấm **Bật dịch trực tiếp**. Trong cài đặt hệ thống, chọn Aurelia Live và bật quyền **Hiển thị trên ứng dụng khác**. Quay lại app, bấm **Bật dịch trực tiếp** lần nữa. Lần đầu cần Internet để tải mô hình dịch của ngôn ngữ đã chọn.
4. Xác nhận chia sẻ toàn bộ màn hình, rồi bấm **Mở Chrome** và vào trang truyện. Dừng cuộn để app nhận diện vùng chữ. Bạn có thể chọn **Dịch lại** hoặc **Dừng** từ thông báo. Nếu đã tắt thông báo, mở app để dừng.
5. Với bản NOVA theo ngữ cảnh, bật **Dùng máy chủ Aurelia NOVA** trước khi khởi động phiên dịch. Nhập địa chỉ HTTPS của server V13.2.1, **Client Key**, tên hoặc mã bộ truyện và số chương. Hãy dùng cùng tên bộ truyện và số chương đã dùng trong NOVA nếu muốn dùng lại từ vựng và quy tắc xưng hô. Có thể dùng địa chỉ HTTPS đã có trong hướng dẫn chia sẻ của bản Windows. `127.0.0.1` trên điện thoại trỏ về chính điện thoại.

Các quyền chụp màn hình và lớp phủ do Android cấp. App đọc vùng hiện trên màn hình trong phiên dịch. OCR và bản dịch nhanh dùng ML Kit trên máy. Khi bật NOVA, app gửi chữ OCR và lời thoại gần nhất đến máy chủ HTTPS đã nhập. App giữ ảnh chụp tạm trong bộ nhớ và API Android này chỉ gửi văn bản.

APK 0.2.0 dùng cùng mã ứng dụng và chữ ký với APK 0.1.0. Có thể cài đè bản đã nhận trước đó. Cập nhật server lên V13.2.1 để nhận chế độ nhanh khi bật NOVA.

## Tốc độ và ngữ cảnh

- Mốc dưới 5 giây áp dụng cho **một khung đọc đang hiển thị**, sau khi dừng cuộn. Trang webtoon dài được dịch theo vùng hiện khi cuộn qua từng phần.
- App đặt ngân sách 4,8 giây cho OCR và bản dịch nhanh, lưu tạm câu đã dịch, và hiện những vùng hoàn thành trước. Font cách điệu, ảnh nhỏ hoặc máy chậm có thể làm thiếu vùng chữ.
- NOVA V13.2.1 dùng chế độ dịch nhanh theo nhóm bong bóng khi app gọi API văn bản. NOVA nhận cùng lúc các vùng OCR theo thứ tự, kèm lời thoại nguồn gần nhất chưa lặp. Nếu NOVA trả kết quả trong lúc bạn còn ở vùng đó, app cập nhật lớp phủ. Phiên NOVA chờ tối đa 45 giây cho vùng đó. Đồng hồ trong thông báo tính từ khung hình đổi, gồm cả thời gian ổn định và OCR đến lúc chuẩn bị lớp phủ.
- Phần phủ dùng nền màu sáng trong khung OCR. Chữ cũ có thể còn hơi thấy qua nền và một câu quá dài có thể bị rút trên màn hình nhỏ. Các màn hình mà Android chặn chụp hoặc chặn lớp phủ nằm ngoài phạm vi của chế độ này.

## Build lại

Mở thư mục `android/` bằng Android Studio hoặc dùng JDK 17+, Android SDK 35 và Gradle Wrapper đi kèm.

```sh
./gradlew assembleDebug lintDebug testDebugUnitTest
```

Trên Windows dùng `gradlew.bat`. APK debug cho từng ABI nằm trong `app/build/outputs/apk/debug/`. Dùng `assembleRelease` và ký bằng khóa phát hành của bạn nếu cần build một bản phát hành riêng.

## Kiểm tra gói

ZIP gốc được ghép từ bốn phần bằng `GHEP_ZIP_GOC.py` và được đối chiếu với SHA-256 sau.

```text
9fa8a9d99bf1deeea09c9f4608e9b24155a0836cae1f68700a4722b5902044a9
```

Bản FULL Android V13.2.1 giữ cấu trúc thư mục của V13.2. Xem thay đổi, cách nâng cấp và kết quả đo mới trong `NOVA_13_2_1_VI.md` ở thư mục gốc. `KIEM_TRA_VI.md` ghi lại phép đo V13.2 trước khi tối ưu.

## Tính năng Android 0.2.1-sync (mã nguồn)

- Giao diện có thẻ giới thiệu và bố cục theo ba bước: ngôn ngữ, bộ máy dịch, đọc truyện.
- Chọn văn phong: sát nghĩa, tự nhiên, theo ngữ cảnh hoặc nhấn nhá (nhấn nhá có thể chậm vì dùng quy trình đầy đủ).
- Cùng **Client Key** với Chrome Extension: dùng chung ngôn ngữ, truyện, chương và văn phong trên server 13.2.2. Nhấn **Lấy cấu hình chung từ NOVA** để nhận bản mới; bấm Bật dịch để gửi cấu hình hiện tại.
- Cùng **tài khoản Reader**: mở Reader web > mục **Liên kết thiết bị** > tạo mã. Vào Android, bật NOVA, nhập HTTPS và mã một lần, nhấn **Liên kết tài khoản NOVA**. Cả ba bề mặt dùng cùng tài khoản nếu Chrome cũng đăng nhập tài khoản ấy.
- Ứng dụng không đồng bộ Client Key, ADMIN_KEY, mật khẩu, ảnh chụp hoặc văn bản truyện trong mục cài đặt. Các bản dịch theo ngữ cảnh vẫn tuân thủ quyền của tài khoản.
- Khi server mất kết nối, Android vẫn có thể dịch nhanh bằng ML Kit nếu mô hình đã tải.

## Build Android trên Windows (bản nguồn)

1. Cài Android Studio, JDK 17 và Android SDK 35 (Android Studio có thể hướng dẫn tải các thành phần).
2. Mở thư mục `android` như một project Android Studio, chờ Gradle Sync hoàn tất.
3. Chọn **Build > Build APK(s)** để sinh debug APK. Hoặc chạy `gradlew.bat assembleDebug` từ thư mục `android`.
4. APK debug nằm trong `android/app/build/outputs/apk/debug/` (kiểm tra ABI thích hợp).
5. Nếu có app 0.2.0 đã cài với chữ ký khác, Android có thể từ chối cài đè; sao lưu cấu hình trước khi gỡ.

Chưa có phép đo mới trên điện thoại thật cho mã nguồn 0.2.1-sync.
