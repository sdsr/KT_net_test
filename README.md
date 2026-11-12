# 망체크

Android 10 이상에서 모바일 통신 품질을 기록하는 개인 설치용 앱입니다. Kotlin / Jetpack Compose / Room / MapLibre Native로 구현합니다.

## 기록 방식

- 사용자가 시작하면 60초 간격을 목표로 `8.8.8.8`과 `1.1.1.1`에 실제 ICMP 핑을 각각 3회 보냅니다. 응답 제한은 2초입니다.
- 기본 데이터 SIM의 셀룰러 네트워크에 검사 소켓을 연결합니다. Wi-Fi로 검사 결과를 대체하지 않습니다. VPN 사용 중에는 검사를 보류합니다.
- 위치·위치 정확도·정보 획득 시각, LTE/NR 셀 정보와 신호 품질, 연결 및 셀 변화, 배터리와 앱 사용량을 기기에 기록합니다.
- 수동 속도 측정은 Cloudflare 서버를 대상으로 HTTP 데이터를 내려받고 올립니다. 방향별 기본 20 MiB, 선택 5/20/100 MiB, 최대 10초입니다. 자동 대용량 속도 검사는 없습니다.
- 지도는 휴대폰의 측정 위치를 표시합니다. 기지국의 실제 설치 좌표를 추정하지 않습니다.
- 기록을 관계형 CSV 파일과 형식 설명을 담은 ZIP으로 내보낼 수 있습니다. 자동 삭제와 클라우드 백업은 기본적으로 사용하지 않습니다.

## 설치·사용

1. 전달된 APK를 Android 휴대폰에 복사하고 해당 파일을 연 앱의 '출처를 알 수 없는 앱 설치'를 허용하여 설치합니다.
2. 앱을 열어 정확한 위치·전화 상태·알림 권한을 허용하고 휴대폰 위치 기능을 켭니다.
3. KT SIM이 기본 데이터 SIM으로 선택됐는지 확인합니다. 앱은 통신사 설정을 바꾸지 않습니다.
4. 화면이 꺼진 동안 계속 기록하려면 앱 배터리 설정에서 제한을 해제합니다. 삼성 단말에서는 절전 앱 목록도 확인합니다.
5. `측정 시작`을 누릅니다. 상태 화면과 상시 알림에서 중지할 수 있습니다.
6. 느려지는 시점에는 `지금 느림`으로 메모하거나 수동 속도 측정을 실행합니다. 최초 속도 측정 전에 데이터 전송과 Cloudflare 정책 안내를 확인합니다.

일부 단말은 앱의 ICMP 소켓 사용을 막을 수 있습니다. 이때 `측정 불가`로 기록하며 TCP 도달 확인을 핑으로 바꾸어 표시하지 않습니다. 재부팅·강제 종료 이후에는 앱에서 직접 다시 시작해야 합니다.

## 해석과 데이터 사용량

- 핑 응답 없음만으로 통신사 장애를 확정할 수 없습니다. 응답 대상이 ICMP를 낮은 우선순위로 처리할 수 있습니다.
- 매분 검사 사이에 발생한 짧은 끊김을 모두 잡지는 못합니다. 연결·셀 이벤트와 관측 시각을 함께 확인하세요.
- 앱/OS 실행 지연, VPN, 수동 속도 측정 중 보류는 요청 실패와 다릅니다. 미제공 값은 0이 아니라 빈 값으로 저장합니다.
- 30일 매분 두 곳에 각 3회, IPv4·56바이트 본문 기준 핑 요청/응답의 IP 계층 계산량은 약 43.5 MB입니다. 지도, 속도 검사, 전송 오버헤드와 실제 청구량은 별도입니다.
- 방향별 20 MiB 설정의 수동 검사는 본문 기준 합계 최대 약 40 MiB를 목표로 합니다. 네트워크 헤더·전송 중 데이터 등은 추가될 수 있습니다. 짧은 제한 검사는 회선의 최대 Mbps를 보장하지 않습니다.
- 앱의 누적 사용량은 Android UID 통계이며 지도·핑·속도 측정 등을 포함합니다. 이동통신사의 과금 통계와 같지 않습니다.
- 속도는 본 앱의 Cloudflare 대상 HTTP 전송 측정 결과입니다. Cloudflare 공식 웹사이트의 알고리즘 또는 그 결과와 동일하지 않습니다. M-Lab은 사용하지 않습니다.

## Windows 빌드

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File tools/bootstrap.ps1
powershell.exe -NoProfile -ExecutionPolicy Bypass -File tools/build.ps1
```

공식 다운로드의 체크섬을 검증하고 JDK 17·Android SDK 36·Build Tools 35.0.0을 프로젝트 `.tools`에 준비합니다. Gradle 8.13과 AGP 8.13.2를 사용합니다. 인터넷 연결과 Android SDK 라이선스 수락이 필요합니다. `bootstrap.ps1`은 SDK 개발 도구 라이선스를 자동 수락합니다.

빌드 결과: `app/build/outputs/apk/debug/app-debug.apk`. 테스트 보고서: `app/build/reports/tests/testDebugUnitTest/`. Lint 보고서: `app/build/reports/lint-results-debug.html`.

APK는 개인 테스트용 debug 서명입니다. 같은 설치 위에 업데이트하려면 프로젝트 `.tools/android-user`의 서명 키를 보존하세요. 키와 `.tools`는 소스 배포에 포함하지 않습니다.

## 개인정보·외부 서비스

위치·셀·메모 기록은 기기에 저장하며 사용자가 내보내기를 선택할 때만 공유합니다. 측정 대상과 지도 제공자는 네트워크 연결에 필요한 공인 IP 및 요청 정보를 볼 수 있습니다.

- Google / Cloudflare: ICMP 응답 검사. [Google ICMP 설명](https://developers.google.com/speed/public-dns/docs/troubleshooting)
- Cloudflare Speed Test: 실제 속도 측정 통신. GPS 좌표·셀·신호 기록을 전송하지 않으며 별도 결과 업로드 API를 호출하지 않습니다. Cloudflare는 IP를 처리하고 익명화된 측정 정보를 공유할 수 있습니다. [서비스 안내](https://speed.cloudflare.com/) / [공식 프로젝트](https://github.com/cloudflare/speedtest)
- OpenFreeMap: 화면에 보이는 지역의 배경 지도 요청. 로컬 측정 GeoJSON을 업로드하지 않습니다. 지도 영역에 해당하는 타일 요청은 제공자에게 전달됩니다. [안내](https://openfreemap.org/quick_start/)

지도 출처: OpenFreeMap / © OpenMapTiles / © OpenStreetMap contributors.

실기기에서의 KT LTE/5G·화면 꺼짐 장시간 동작은 단말·OS에 따라 달라집니다. 최종 검증 결과는 `VALIDATION.md`를 확인하세요.
