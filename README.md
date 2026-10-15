# sdyl-app —— 安全管控平台轻量客户端

面向**普通外操员工**重建的移动巡检客户端。范围：**巡检（核心）+ 视频监控**，支持现场 **AS1000** 蓝牙测振仪。
不做：定位、报警/应急/推送、作业票、EAM/WMS/WOM/LIMS 等业务域。

- 服务端：`https://supos.example.com:8443`（原平台）
- 风格：Material 3 Expressive（大圆角、动态取色、弹簧动效）
- 全开源技术栈，无厂商 SDK 锁定

## 当前进度

| 阶段 | 内容 | 状态 |
|---|---|---|
| 1 | 工程骨架 + MD3E 主题 + 登录鉴权 + 巡检任务列表 | ✅ |
| 2 | 任务详情（区域）→ 区域作业（设备 → 巡检项）→ 巡检项录入（实值/结论/跳过/异常/备注） | ✅ |
| 3 | 离线库（Room）+ 本地优先读写 + 结果落库（dirty 标记待上传） | ✅ 骨架完成，上传器待做 |
| 4 | 区域签到（扫码/NFC/手动）+ 标签解析 + 拍照留证 | ⏳ |
| 5 | AS1000 蓝牙测振 | ⏳ |
| 6 | 视频监控（Media3 + WebView 兜底） | ⏳ |

**实测体积**：debug `19.21 MB` · **release（R8）`1.69 MB`** —— 对比原版 `117.6 MB`（-98.6%）。
19 个 Kotlin 文件 / 2769 行，对应原版这些功能约 250+ 个类。

## 离线优先设计（已落地）

```
在线：syncTasks / syncDevices / syncWorkItems  →  写入 Room
离线：页面只 observe Room 的 Flow，断网照常浏览与录入
录入：work_item 上直接写 realValue/concluse/skipReason/abnormalReason/remark，
      并置 dirty=true   →  联网后由上传器消费（待做）
```

数据库 `sdyl_patrol.db`，4 张表：`task` / `task_area` / `task_device` / `work_item`。
Room schema 版本化输出在 `app/schemas/`。


## 构建

```bash
# 环境：JDK 17 + Android SDK (compileSdk 36) + Gradle 8.11.1
./gradlew assembleDebug     # 产物 app/build/outputs/apk/debug/app-debug.apk
./gradlew assembleRelease   # R8 压缩后体积大幅下降
```

`local.properties` 里写 `sdk.dir=<Android SDK 路径>`。

### 私有配置（不入库）

真实环境信息不写进仓库，放在本地未跟踪的 `local.properties`：

```properties
sdyl.defaultServer=your-server:port   # 构建时注入 BuildConfig.DEFAULT_SERVER，作为登录页默认服务器地址
```

不配置时使用占位地址 `supos.example.com:8443`（仓库里所有示例值都是占位符）。
服务器地址也可以在 App 的登录页直接手填，登录后保存在本机。

## 目录结构

```
app/src/main/java/com/sdyl/patrol/
├─ SdylApp.kt                      # Application + Graph（轻量依赖容器）
├─ MainActivity.kt
├─ core/
│  ├─ data/SessionStore.kt         # 服务器地址 / 票据 / 用户（SharedPreferences）
│  └─ network/
│     ├─ ApiClient.kt              # OkHttp + Retrofit + 鉴权头 + 自签证书放行
│     ├─ ApiEnvelope.kt            # BapResponse / BapPage / PageRequest
│     └─ dto/AuthDto.kt            # 登录相关 DTO
├─ feature/
│  ├─ auth/                        # 登录（AuthApi + AuthRepository + LoginScreen）
│  ├─ patrol/                      # 巡检（PatrolApi + Repository + TaskListScreen + Model）
│  ├─ me/                          # 我的 / 退出登录
│  └─ video/                       # 视频监控（占位）
└─ ui/
   ├─ AppNav.kt                    # 底部导航：巡检 / 视频 / 我的
   └─ theme/Theme.kt               # MD3E 主题（动态取色 + 大圆角 + 弹簧动效）
```

## 与原 App 的协议对齐（关键，不能改）

| 项 | 值 |
|---|---|
| 业务请求头 | `Authorization: Bearer <ticket>` + **`BapServer: <host:port>`**（缺 BapServer 会被网关拒） |
| User-Agent | `<系统 UA>/mesjg supMobile/6.2.8.35` |
| 登录 | `POST /inter-api/auth/login` `{userName,password,clientId:"android",userAgent}` |
| 登录后必办 | `GET /inter-api/auth/v1/getCurrentLoginInfo`（业务请求依赖其中的 staffId/companyId） |
| 续期 | `PUT /inter-api/auth/token/refresh` |
| 成功码 | `code ∈ {200, 100000000, 2000000}` |
| 巡检任务列表 | `POST /msService/PATROL/getMyTaskListArea` |
| 区域内设备 | `POST /msService/PATROL/getTaskAreaDevice` |
| 设备巡检项 | `POST /msService/PATROL/getTaskAreaDetail` |
| 基础数据同步 | `POST /msService/PATROL/getPatrolBaseDatas` |
| 结果上传 | `POST /msService/PATROL/uploadOnLineTaskResult?isOnLine=` |

完整 694 条接口清单见 `../recon/endpoints.txt`。

## 已知待办

1. **密码加密**：`GET /inter-api/auth/v1/password/config` 返回 `encrypted=true` 时密码需要按平台约定的对称加密后再提交
   （本环境实测为 `false`，明文即可；具体密钥/算法属平台私密信息，未写入本仓库）。
2. **401 自动重登**：原 App 会重登并重放原请求（尚未实现）。
3. **MaterialExpressiveTheme**：material3 1.4.0 中仍为 internal，当前用公开 API 复刻同等视觉；
   待 1.5.0 正式版发布后切回 `MaterialExpressiveTheme(motionScheme = MotionScheme.expressive())`。
