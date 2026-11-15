# BOB-Car(법카)

**돌려 타는 법인차가 스스로 운행·비용 장부를 쓰는 Pleos Connect 차량 앱**

법인차 운행기록부는 보통 운전자가 나중에 손으로 채웁니다. 그래서 빠지는 운행이 생기고, 법인카드 충전·주유 결제가 실제 운행과 맞는지 확인하기 어렵습니다.
BOB-Car는 차가 탑승·출발·종료를 직접 잡아 **계기판 누적 거리로 운행기록부를 쓰고**, 그 기록을 **예약·법인카드 결제와 대조해 어긋남을 찾습니다.**

---

## APK 위치

| 파일 | 설치 대상 | 비고 |
|---|---|---|
| [`apk/뚜따_BOB-Car_release.apk`](apk/) | **Pleos Connect 에뮬레이터** | 출품작 본체(차량 앱) |
| [`apk/뚜따_BOB-Car-mobile_release.apk`](apk/) | 일반 Android 휴대폰·에뮬레이터 (Android 9 이상) | 운전자용 휴대폰 앱 (심사 참고 자료) |

두 APK 모두 서버(Firebase, 서울 리전)에 연결된 release 빌드입니다. 관리자 웹은 **https://bob-car-a4847.web.app** 에서 바로 열 수 있습니다.

---

## 주요 기능

| 단계 | 차에서 일어나는 일 |
|---|---|
| ① 체크인 | 운전석에 앉으면 운전자 카드가 뜹니다. 기본값은 지금 시간 예약자 → 직전 운전자 순. **1탭 확인** 또는 [다른 사람]. 확인하지 않고 출발하면 기본값으로 잠정 기록하고 음성으로 알립니다. 운전자의 첫 체크인에는 **수집 동의 카드**가 뜹니다 |
| ② 브리핑 | 배터리 %, 누적 거리, 미기록 운행 여부를 화면과 음성으로 안내합니다 |
| ③ 주행 기록 | 출발 누적 거리·시각, 내비 목적지, 예상 통행료를 서비스가 자동으로 기록합니다. 주행 중에는 **버튼 없는 표시 전용 화면**만 보입니다 |
| ④ 운행 종료 | 기어 P를 1초 유지하면 "운행 종료까지 10초" 카드. 운전자와 용도(업무·출퇴근·개인)를 확정합니다. 하차(운전석 비움·운전석 문 열림)로도 확정됩니다 |
| ⑤ 인수인계 | 종료 시각·누적 거리·배터리 %를 서버에 저장해 다음 운전자 브리핑의 기준으로 씁니다 |

서버는 차량 기록을 다른 자료와 대조해 **플래그**를 만듭니다.

- **충전·주유 결제 대조**: 법인카드 결제가 차에서 감지한 충전량(배터리 % 증가 × 용량)으로 설명되지 않으면 플래그
- **미기록 운행**: 직전 운행 종료와 다음 탑승 사이에 누적 거리가 0.5 km 넘게 늘었으면 플래그
- **예약 밖 사용**: 확정 운전자가 예약자와 다르거나 예약 없는 시간에 쓰면 플래그

관리자 웹(총무팀)은 플래그 목록과 국세청 업무용승용차 운행기록부 양식(엑셀 내려받기)을 보여 줍니다. 휴대폰 앱은 운전자가 자기 운행을 확인하고 운전자·용도를 고치거나 메모(소명)를 남기는 데 씁니다.

---

## 실행 방법 (차량 앱)

### 1. 설치와 실행

Pleos Connect 에뮬레이터를 켠 뒤:

```bash
adb install -r apk/뚜따_BOB-Car_release.apk
```

```bash
adb forward tcp:8088 tcp:8088
```

- 앱 목록의 **법카**를 실행합니다. 따로 허용할 권한은 없습니다(차량 권한은 모두 선언만으로 허용됨).
- Virtual Console은 브라우저에서 http://127.0.0.1:8088/ 을 엽니다. `adb forward`는 에뮬레이터를 켤 때마다 다시 실행합니다.

### 2. 한 번 운행해 보기 (Virtual Console 조작)

| # | Virtual Console | 차량 화면 |
|---|---|---|
| 1 | **Seat Occupancy** → Driver → **Occupied** | 운전자 카드 "김민준 님이 운전하시나요?" (지금 시간 예약자) → **[확인]**. 처음이면 수집 동의 카드 → **[동의]** |
| 2 | **Brake Pedal** 값을 올림 (기어 P) | 체크인 마감 |
| 3 | **Gear Selection** → **D** | 운행 중 화면 (출발 시각, 주행 거리, 목적지, 예상 통행료) |
| 4 | Motion → **Odometer**에 지금보다 큰 값 입력 (예: +22) | 주행 거리 증가 |
| 5 | **Gear Selection** → **P**, 그대로 둠 | "운행 종료까지 10초" 카드 → 용도 확인 → 종료 후 대기 화면에 마지막 운행 |
| 6 | 관리자 웹 https://bob-car-a4847.web.app → 운행기록부 | 방금 운행이 보임 |

- 1번에서 **[다른 사람]**을 골라 예약자가 아닌 직원으로 운행하면 관리자 웹에 **예약 밖 사용** 플래그가 생깁니다.
- 앱을 끈 상태(또는 운전석이 빈 상태)에서 Odometer를 올린 뒤 다시 탑승하면 브리핑과 관리자 웹에 **미기록 운행**이 나옵니다.
- 대기 화면을 기어 P에서 길게 누르면 진단 화면(차량 신호·이벤트)으로 바뀌고, 다시 길게 누르면 돌아옵니다.
- 직원·차량·예약은 시드 데이터입니다(가상의 이름). 서버는 여러 사람이 함께 쓰므로 다른 시연 기록이 함께 보일 수 있습니다.

---

## 테스트 환경

| 항목 | 값 |
|---|---|
| 에뮬레이터 | **Pleos Connect v2.1.0** (AVD `Pleos_Connect_v2.1.0`, Android 14 / API 34, x86_64, user 빌드) |
| 화면 규격 | **2560 × 1440, 160 dpi** (앱 영역 1680 × 1210 dp, 운전자 사용자 10번에서 실행) |
| Pleos SDK | Vehicle 2.3.2, NaviHelper 3.0.5, TextToSpeech 3.3.1 (기기 안 모드 ON_DEVICE) |
| Android 빌드 | Gradle 8.10.2, AGP 8.8.2, Kotlin 2.0.0, JDK 21로 실행, minSdk 28 / targetSdk 35 |
| 휴대폰 앱 확인 | Android Studio 휴대폰 AVD (Medium Phone, Android 15) |
| 서버 | Firebase Cloud Functions(Node.js 22) + Firestore, 서울 리전(asia-northeast3), Firebase Hosting(관리자 웹) |

필요한 설정은 `adb forward tcp:8088 tcp:8088`(Virtual Console)뿐입니다. 차량 값(탑승, 기어, 브레이크, 누적 거리, 배터리, 운전석 문)은 모두 Virtual Console에서 바꿉니다.

---

## 쓰는 차량 신호

| 신호 | 읽는 방법 | 쓰임 |
|---|---|---|
| 운전석 탑승 | Pleos `Seat` 탑승 상태 | 체크인 시작, 하차로 운행 종료 |
| 기어 | Android `GEAR_SELECTION` (1초 유지 값만) | 출발(P → D/R), 종료 카운트다운(P) |
| 브레이크 | Pleos 브레이크 페달 상태 | 체크인 마감(P에서 밟음) |
| 누적 거리 | Pleos `Odometer` | 운행 거리 = 종료 − 시작 (계기판 기준) |
| 배터리 | Pleos 배터리 % × Android 배터리 용량 | 충전량 계산, 브리핑 |
| 운전석 문 | Pleos 문 열림 | 하차 감지 보조 |
| 내비 | NaviHelper 목적지·경로 시작(예상 통행료)·즐겨찾기(집·회사) | 목적지, 통행료, 용도 기본값(집이면 출퇴근) |

---

## 주행 안전 설계

- 확인·선택·동의처럼 **입력이 필요한 카드는 기어 P에서만** 띄웁니다. 카드가 떠 있을 때 P에서 벗어나면 바로 닫고 기본값으로 잠정 기록한 뒤, 운행 종료 카드에서 확정합니다. D로 서 있어도 주행 중으로 봅니다.
- 주행 중(D/R)에는 버튼 없는 **운행 중 화면만** 보입니다. 이를 위해 메인 화면을 주행 중 사용 가능(`distractionOptimized`)으로 선언했습니다.
- 주행 중 안내는 **음성(TTS, 기기 안 모드)**으로 합니다: 체크인 결과, 브리핑, 종료 카운트다운, 운전자 미확인 출발. 음성이 실패해도 화면만으로 동작합니다.

## 개인정보와 보안

- 운전자별 첫 체크인 때 수집 항목·목적을 알리고 동의를 받습니다. **동의 전 기록은 차량에만 보관**하고 서버로 보내지 않습니다.
- 서버에는 앱이 만든 차량 ID·운전자 ID만 보냅니다(VIN 미전송). **개인 용도 운행은 목적지를 저장하지 않습니다.**
- release 빌드는 HTTPS만 사용(평문 통신 차단), `allowBackup=false`, 디버그 비활성화. 로그의 개인정보는 마스킹합니다.
- 서버 인증은 Firebase 익명 로그인 ID 토큰을 `Authorization` 헤더로 보냅니다(URL에 토큰 없음). 저장소에는 키·토큰·키스토어가 없습니다.
- 서버가 꺼져 있거나 연결이 끊겨도 차량 앱은 기기 안에 먼저 저장하고, 연결되면 업로드 큐로 다시 보냅니다.

---

## 시스템 구성

```
차량 앱 (Pleos Connect) ──HTTPS──▶ 서버 (Firebase Functions + Firestore, 서울)
                                      ▲                ▲
                       휴대폰 앱 ─────┘                └───── 관리자 웹 (Firebase Hosting)
```

차와 휴대폰은 직접 통신하지 않습니다. 차량 앱은 휴대폰 앱 없이 단독으로 동작합니다.

## 폴더 구조

| 폴더 | 내용 |
|---|---|
| `apk/` | 제출용 release APK (차량 앱, 휴대폰 앱) |
| `android/` | Android Studio 프로젝트 (이 폴더를 엽니다) |
| `android/vehicle-app/` | 차량 앱 (Kotlin, Jetpack Compose, `com.bobcar.vehicle`) |
| `android/mobile-app/` | 휴대폰 앱 (`com.bobcar.mobile`) |
| `android/shared/` | 두 앱이 함께 쓰는 서버 연결 코드·수집 고지 문구 |
| `server/` | Firebase Cloud Functions(TypeScript) + Firestore 규칙 |
| `admin-web/` | 관리자 웹 (플래그 목록, 운행기록부) |

---

## 직접 빌드하기

### Android 앱

`android/` 폴더를 Android Studio로 열거나, 그 폴더에서:

```bash
./gradlew :vehicle-app:assembleDebug
```

- **debug 빌드**는 PC에서 실행한 로컬 Firebase 에뮬레이터(`http://10.0.2.2`)에 연결됩니다.
- **release 빌드**는 `~/.gradle/gradle.properties`의 `bobcar.release.serverUrl`, `bobcar.release.firebaseApiKey`(서버)와 `bobcar.release.storeFile` 등(서명)을 읽습니다. 값이 없으면 서버 없이 기기 안 기록만 하는 앱으로 빌드됩니다.

### 서버 로컬 실행 (Firebase 에뮬레이터)

Node.js 22, Firebase CLI, JDK 21이 필요합니다. `server/functions` 폴더에서:

```bash
npm install
```

```bash
npm run build
```

```bash
npm run emulators
```

- 관리자 웹은 http://127.0.0.1:5000/ 입니다. 시드 데이터(차량·직원·오늘 예약)는 첫 요청 때 자동으로 들어갑니다.
- 시연용 법인카드 결제 입력: `npm run demo:payment -- 12000` (충전 12,000원). 서버 통합 테스트: `npm run test:emulators`
- 배포: `server` 폴더에서 `firebase deploy --project release --only functions,firestore,hosting`

---

## 에뮬레이터에서 알아 둘 점

- 에뮬레이터는 주행해도 누적 거리가 늘지 않아 Virtual Console의 **Odometer에 값을 직접 입력**합니다. 에뮬레이터를 다시 켜면 누적 거리가 초기화됩니다.
- 에뮬레이터에는 시동 신호가 없어 운행 종료는 **기어 P 10초 카운트다운**과 **하차 감지**로 잡습니다.
- Vehicle SDK 2.3.2는 운전석 탑승 값이 반대로 오는 알려진 문제가 있어(차기 버전에서 수정 예정) 앱이 SDK 2.3.2일 때만 한 곳에서 보정합니다.
- 에뮬레이터는 전기차라 충전(배터리 %)으로 대조합니다. 서버에는 주유 결제 판정 규칙(리터당 단가)도 있지만, 차량 쪽 연료 % 읽기는 실차 대응 향후 계획입니다.

## 라이선스 고지

- 아이콘: [Phosphor Icons](https://phosphoricons.com/) (MIT) — `admin-web/icons/`, `android/mobile-app/licenses/`
- 글꼴: [Inter](https://rsms.me/inter/) (SIL Open Font License) — `admin-web/fonts/`
