# SoftApHelper (Xposed)

SoftAp static server IP(v4) for Android 9-16

SoftAp type hide for Android 10-16

SoftAp 5G channel and bandwidth lock for Android 13-16 

SoftAp IPv6 tethering off for Android 9-16

## 功能
1. 固定IP地址 (Android 9-16)
2. 隐藏热点类型 (Android 10-16)
3. 锁定5G信道和频宽 (Android 13-16)
4. 关闭IPv6中继 (Android 9-16)
5. 开机自动开启共享 (Android 11-16)

## 务必先确认作用域（Android 12+ 必看）
模块的`xposed_init`/`xposedscope`只是**推荐**作用域，**不会自动生效**。

部分框架（例如 `Vector`）只把推荐作用域显示在选择列表里，需要你自己勾选并保存：

`LSPosed/Vector` - 模块 - SoftApHelper - **作用域** - 勾选：

- **系统框架**（`system`）
- **`com.android.networkstack.tethering`**

保存后**重启手机**（作用域要重启才生效）。

作用域为空时模块不会加载到任何相关进程，表现为**完全没效果**，而且日志里不会有任何 `SoftApHelper` 记录。

自检方法：重启后在 LSPosed/Vector 日志里搜索 `SoftApHelper`，正常应能看到

```
[SoftApHelper] [Success]: [requestIpv4Address] found in com.android.networkstack
[SoftApHelper] [Success Edit]:192.168.43.1/24
```

## 安卓14已知问题：
部分安卓14系统由于存在缓存，需要手动**重新优化Tethering**，模块才能生效

LSPosed-模块-SoftApHelper-长按Tethering-重新优化-重启手机

[图片示例](https://xhy-1252675344.cos.ap-beijing.myqcloud.com/imgs/softap-redex.jpg)

## 注意
**网络前缀冲突**会导致网络连接失败（`Android 10`及以下）或仍使用随机IP（`Android 11`及以上，日志提示`isConflictPrefix`）。

支持设置`WIFI`、`USB`、`蓝牙`、`P2P`、`以太网`的热点IP（`Android 11`及以上），网段可以在**模块界面**里直接改，见下面的[配置网段](#配置网段)。

默认值：

| Type      | IP                             |
|-----------|--------------------------------|
| USB       | 192.168.42.1/24                |
| WIFI      | 192.168.43.1/24                |
| BlueTooth | 192.168.44.1/24                |
| P2P       | 192.168.49.1/24                |
| ETHERNET  | 192.168.45.1/24                |

`43.1`**连不上的可以在界面里改成 `192.168.1.1/24` 试试**。

安卓13+开启5G热点时，如果未指定5G信道(未指定单个channel或者使用allowedAcsChannels)，模块将锁定频段为`149,153,157,161,165`，最大频宽为`320MHZ`(受硬件限制，实际可能只有`80MHZ`)。

如果需要锁定频段为其他范围（比如`36,40,44`），请使用[VPNHotspot](https://github.com/Mygod/VPNHotspot)，填写`5 GHz ACS 可选频段`。

## 配置网段
模块界面（LSPosed/Vector 里点开模块，或桌面上有图标的话直接打开）里，每个共享方式一个按钮，点进去填「本机地址/前缀长度」再保存：

```
选择要配置的共享方式
  WiFi 热点配置        192.168.43.1/24
  USB 共享网络配置      192.168.42.1/24
  蓝牙共享网络配置      192.168.44.1/24
  WiFi P2P 配置        192.168.49.1/24
  以太网共享配置        192.168.45.1/24
```

规则：

- 格式是 `地址/前缀长度`，例如 `192.168.43.1/24`；只填 IP 时按 `/24` 处理
- 填的是**共享后本机自己的地址**（对端看到的网关），不是网段号；`192.168.43.0/24` 这种网络地址和广播地址会被拒绝
- 前缀长度 1~30
- **保存前会检查网段冲突**，重叠时直接拦截、红字提示和谁冲突了，不写进配置：
  - 和其它共享方式已配置的网段（例如把 USB 的网段填给了 WiFi）
  - 和设备当前在用的网络（Wi-Fi / 移动数据 / VPN / 正在跑的热点），例如手机连着 `192.168.3.0/24` 的 Wi-Fi 时热点不能再配成这个网段

  前缀冲突会让共享连不上（Tethering 自己也会拒绝，日志里的 `isConflictPrefix`），所以在界面上提前拦住。
- 配置存在模块的 SharedPreferences 里（框架的 `xposedsharedprefs` 机制），所以 manifest 里必须有 `<meta-data android:name="xposedsharedprefs" android:value="true"/>`

### WiFi 热点的频段和信道
WiFi 热点配置页里还能选**频段**（跟随系统 / 2.4G / 5G / 2.4G+5G）和**信道**：

- 选单频段（2.4G 或 5G）时热点只开这个频段，信道下拉框生效，可以从该频段的标准信道里选一个，也可以选「自动」交给系统挑
- 选 2.4G+5G 时由系统在双频之间自己选信道，信道下拉框会置灰
- 「跟随系统」= 模块不改写，保持系统原来的行为（默认值）

实现上改的是 `SoftApConfiguration` 的 `channels` 参数。注意 **`SoftApConfiguration` 构造一次后会被
`WifiApConfigStore` 缓存复用**，所以只改构造函数参数在「用户改完配置、重开热点」时不生效，
必须同时 hook `getChannels()` —— Wi-Fi 模块在 `HostapdHalAidlImp.prepareNetworkParams` 里正是
通过这个 getter 拿频段/信道的（`band = config.getChannels().keyAt(i)`）。

验证办法：开完热点后看 hostapd 的实际配置

```bash
su -c 'grep ^channel= /data/vendor/wifi/hostapd/hostapd_wlan2.conf'
```

`dumpsys wifi` 里的 `frequency=` 是**中心频率**（80MHz 时和主信道不是一回事），别被它误导。

### IPv6 中继
每个共享方式的配置页里都能配 **IPv6 中继**，两个选项：

- **跟随系统**（默认）= 模块不改写，系统原来怎么下发就怎么下发
- **关闭** = 不再把上游的 IPv6 前缀下发给客户端

关闭后客户端**只有 IPv4**（IPv6 只剩 link-local），适合 Clash 这类代理不支持 IPv6 的场景——
否则客户端会自己 SLAAC 出公网 IPv6，绕开代理和规则直连，分流就失效了。

拦的是 `android.net.ip.IpServer#updateUpstreamIPv6LinkProperties`：把传进来的上游 IPv6-only
`LinkProperties` 换成 `null`，IpServer 就切到「上游没有 IPv6」这条路径（AOSP 自己在上游 IPv6
掉线时发的也是 `null`，所以不是硬造出来的中间态）：

- RA 里不带前缀、不带默认路由，已经发出去过的前缀还会按 lifetime 0 作废 → 客户端 SLAAC 不出全局地址
- RDNSS（IPv6 DNS）一并去掉
- IPv6 转发规则（BPF offload / ip6tables）一起清掉 → 客户端手里就算留着旧地址也过不去

IPv4 完全不受影响。

**注意**：上游是 IPv6-only（464XLAT）时关掉会让客户端没网；除了「跟随系统」没有「强制开启」，
上游没有 IPv6 时模块也造不出来。

**什么时候才真的有 IPv6 可关**：AOSP 只在上游是**蜂窝网络**时才做 IPv6 中继 ——
`TetheringInterfaceUtils#allowIpv6Tethering` 只认 `TRANSPORT_CELLULAR` 和 `TRANSPORT_TEST`。
也就是说用 **Wi-Fi 上游**共享时，客户端本来就拿不到全局 IPv6（平台自己就不中继），
这时「关闭」是空操作；真正会遇到问题的是**移动数据共享 + 代理不支持 IPv6** 的场景。
（真机验证时如果没有 SIM，抓不到 `[Success Edit]: disable IPv6 relay` 这行日志是正常的。）

**生效时机**：和网段一样，保存后会重启一次该共享。这一步是必须的——RA daemon 起来后会一直按
之前 build 好的 `RaParams` 周期发 RA，只改配置不重启，要等上游 IPv6 变化才会重新走到这个 hook。

日志（Tethering 进程里）：

```
[SoftApHelper] [Success]: [updateUpstreamIPv6LinkProperties] found in com.android.networkstack.tethering
[SoftApHelper] [Success Edit]: disable IPv6 relay (interfaceType 0)
```

客户端侧确认：`ip -6 addr`（Windows 是 `ipconfig`）应该只剩 `fe80::` 开头的地址。

### 保存后自动重启共享
点保存后，模块会自动把对应的共享**关掉再打开一次**，新网段立刻生效，不用自己去点快捷开关。

- 只有当前**正在共享**的类型才会重启；没在开启的直接跳过，下次开启时就是新网段
- 实现：界面发一条带 signature 权限保护的广播，system_server 里的模块代码收到后调
  `TetheringManager.stopTethering/startTethering`（公开 API，Android 14+）
- 停止后等 2.5 秒再启动：Wi-Fi 关 AP 是异步的，立刻启动会撞上 Wi-Fi 模块的
  "Tethering is already active or activating"，最终返回 `TETHER_ERROR_INTERNAL_ERROR(5)`；
  启动失败会自动重试，最多 4 次
- **需要作用域勾选「系统框架」**，因为这段代码跑在 system_server 里；没勾就退化成"需要手动开关一次"
- 重启共享会让已连接设备短暂断开（约 5 秒）

### 开机自动开启
每个共享方式的配置页里都有一个**开机自动开启**开关（默认关闭）。打开后设备每次开机完成时，
模块会自动把这种共享打开，不用自己去点快捷开关；主界面的按钮上会在网段后面标一个「开机自启」。

- 关机前这种共享是开是关都不影响：只看这个开关和「开机」这个时机
- 检测到开机完成后**等 5 秒**再开（这时 Wi-Fi / Tethering 还在初始化，早开会被拒），
  开不起来每 5 秒重试一次，最多约 2 分钟
- 只对**正在跑的共享**做判断，已经开着的类型直接跳过
- 需要 **Android 11+**（`TetheringManager` 从 Android 11 起才有），而且和「保存后自动重启共享」
  一样**需要作用域勾选「系统框架」**——这段代码跑在 system_server 里，没勾就不会有任何反应

实现上：system_server 里动态注册 `BOOT_COMPLETED` 接收器，另外每 10 秒看一眼
`sys.boot_completed` 兜底（万一某个 ROM 不让动态注册的接收器收系统保护广播，或者模块加载得比
广播还晚），两者靠一个标志位保证只生效一次；然后按类型调 `TetheringManager.startTethering`——
Android 14+ 用带回调的 `TetheringRequest` 版本，11~13 只有 SystemApi 的
`startTethering(int, Executor, StartTetheringCallback)`（公开 SDK 里没有，模块用反射调，
Android 14 起它已被标记废弃）。

日志（system_server 里）：

```
[SoftApHelper] [Boot]: detected by sys.boot_completed, auto start in 5s.
[SoftApHelper] [Boot]: auto start tethering, type 0 ...
[SoftApHelper] [Success]: tethering started (type 0).
```

第一行里的 `detected by` 会写清楚这次是开机广播还是兜底轮询发现的（`BOOT_COMPLETED` / `sys.boot_completed`）。

**实测**（OnePlus 8T / LineageOS 23，Android 16）：`sys.boot_completed` 在开机约 15 秒时就能读到，
而 `BOOT_COMPLETED` 广播要再晚 **40 秒左右**才送到（`[Boot]: BOOT_COMPLETED received.` 出现在热点起来之后
是正常的，此时它已经不需要做什么了）。所以真正干活的基本都是兜底轮询，开机广播是道保险——
`sys.boot_completed` 万一读不到（或者模块加载得比它还晚），还有广播这条路。

没生效时先看有没有 `[Boot]` 这行：没有的话说明开机广播/兜底检查没跑到（多半是作用域没勾
「系统框架」，或者这个共享方式的开关没打开）；有 `[Boot]` 但后面是 `[Error]`，把错误码和日志一起提 issue。

顺带一提，system_server 自己重启（不是整机重启）时 `sys.boot_completed` 还是 1，模块会按
「开机」处理把共享重新打开。

## 下载
[Release](https://github.com/XhyEax/SoftApHelper/releases)

## 作用域
**推荐作用域需要手动勾选**（LSPosed/Vector 都不会自动应用，详见文首说明）。
### 安卓11及以下
系统框架

### 安卓12及以上（以及部分安卓11设备）
注意：高版本LSPosed勾选Tethering失败是正常现象，不影响插件生效

系统框架（一般只钩这个就可以了，勾选Tethering是保险起见）

`com.google.android.networkstack.tethering.inprocess`

`com.android.networkstack.tethering.inprocess`

`com.google.android.networkstack.tethering`

`com.android.networkstack.tethering`

## 连接测试&问题反馈
开启热点后，手机端使用`ifconfig`命令查看IP（或usb连接电脑后，进入`adb shell`执行）。或使用其他机器连接热点后，`ping 192.168.43.1`。

如果插件未生效，作用域可尝试勾选更多包名包含`networkstack.tethering`的应用。

若仍未生效，请上传设备执行`ifconfig`的结果，以及`/apex/com.android.tethering/priv-app/`下的apk到[Issues](https://github.com/XhyEax/SoftApHelper/issues)。

## 关于热点IP自定义
已实现：见上面的[配置网段](#配置网段)，在模块界面里改，存在模块自己的 SharedPreferences 里，
hook 侧用 `XSharedPreferences` 读（依赖框架的 `xposedsharedprefs` 机制，工作方式是把模块的
`getPreferencesDir()` 重定向到框架目录，所以界面进程和系统进程看到的是同一份文件）。

## 原理
[安卓9 固定Wifi热点IP (Xposed)](https://blog.xhyeax.com/2021/03/01/android-9-set-hotpot-ip/)

[安卓10、11 固定Wifi热点IP (Xposed)](https://blog.xhyeax.com/2021/12/06/android-10-11-hostpot-set-ip/)

[安卓12 固定Wifi热点IP (Xposed)](https://blog.xhyeax.com/2022/07/06/android-12-hostpot-set-ip/)

## 固定热点IP-Hook点
### 安卓9
`com.android.server.connectivity.tethering.TetherInterfaceStateMachine`的`getRandomWifiIPv4Address`函数。

[TetherInterfaceStateMachine.java#259](http://aospxref.com/android-9.0.0_r61/xref/frameworks/base/services/core/java/com/android/server/connectivity/tethering/TetherInterfaceStateMachine.java#259)
```java
private String getRandomWifiIPv4Address()
```

### 安卓10
`android.net.ip.IpServer`的`getRandomWifiIPv4Address`函数。

[IpServer.java#469](http://aospxref.com/android-10.0.0_r47/xref/frameworks/base/services/net/java/android/net/ip/IpServer.java#469)
```java
private String getRandomWifiIPv4Address()
```

### 安卓11
`android.net.ip.IpServer`的`requestIpv4Address`函数。

[IpServer.java#645](http://aospxref.com/android-11.0.0_r21/xref/frameworks/base/packages/Tethering/src/android/net/ip/IpServer.java#645)
```java
private LinkAddress requestIpv4Address()
```

由于该函数还被用于其他方式的网络共享及更换前缀，所以需要判断网络类型（`mInterfaceType == TETHERING_WIFI`）和调用者（遍历堆栈查找`configureIPv4`），最后进行替换。


### 安卓12
`android.net.ip.IpServer`的`requestIpv4Address`函数。

[IpServer.java#655](http://aospxref.com/android-12.0.0_r3/xref/packages/modules/Connectivity/Tethering/src/android/net/ip/IpServer.java#655)
```java
private LinkAddress requestIpv4Address(final boolean useLastAddress)
```

### 安卓13
Hook点同安卓12

[IpServer.java#664](http://aospxref.com/android-13.0.0_r3/xref/packages/modules/Connectivity/Tethering/src/android/net/ip/IpServer.java#664)
```java
private LinkAddress requestIpv4Address(final boolean useLastAddress)
```

### 安卓14
Hook点同安卓12（参数有变化，但函数名没变）

[IpServer.java#684](http://aospxref.com/android-14.0.0_r2/xref/packages/modules/Connectivity/Tethering/src/android/net/ip/IpServer.java#684)
```java
private LinkAddress requestIpv4Address(final int scope, final boolean useLastAddress)
```

### 安卓15 / 安卓16
Hook点同安卓14，函数名和参数都没变，`configureIPv4`也没有被R8内联：

```java
private LinkAddress requestIpv4Address(final int scope, final boolean useLastAddress)
private boolean configureIPv4(boolean enabled, int scope)
```

安卓16 的变化只在内部实现：`IpServer` 不再持有 `PrivateAddressCoordinator`（地址改由 `RoutingCoordinator`/`ConnectivityService` 分配），因此取不到该字段时模块直接跳过前缀冲突检查；`mInterfaceType` 字段仍然存在。

若在 Android 16 上无效，先确认作用域（见文首），再看日志里有没有上面的 `[Success]` 行。

## 隐藏热点类型
`android.net.dhcp.DhcpServingParamsParcelExt`的`setMetered`函数。

```java
    /**
     * Set whether the DHCP server should send the ANDROID_METERED vendor-specific option.
     *
     * <p>If not set, the default value is false.
     */
    public DhcpServingParamsParcelExt setMetered(boolean metered) {
        this.metered = metered;
        return this;
    }
```

### 安卓15
Hook点同安卓14

## 固定5G热点信道
### 方法1：使用本插件
（TODO）安卓12及以下：指定AP频段为特定信道。

安卓13+：如果开启5G热点时，未指定5G信道(单个channel或者allowedAcsChannels)，锁定频段为`149,153,157,161,165`，频宽为`320MHZ`(受硬件限制，实际可能只有`80MHZ`)。

**已知限制**：模块按 `SoftApConfiguration.getChannels()` 里是否存在 `BAND_5GHZ` 这个 key 来判断是否锁5G。
部分系统（例如双频`2.4G+5G 自动`）写入的是合并后的 key（`BAND_2GHZ|BAND_5GHZ = 3`，
`dumpsys wifi` 里显示 `Channels = {3=0}`），此时 key 不等于 `BAND_5GHZ`，模块不会改写 ACS 频段。

**更关键的限制**：这套「锁 ACS 频段」依赖 ROM 打开 ACS offload（`config_wifi_softap_acs_supported`）。
很多 ROM（例如 OnePlus 8T 的 LineageOS 23）这个值是 `false`，框架压根不会把允许的信道列表传给 hostapd，
hostapd 收到的是 `channel=0` 自己选。这种情况下任何"锁 149~165"的做法都不可能生效——**想固定信道，
直接在模块界面里选频段+信道**（见下面的配置网段），模块会改写 `SoftApConfiguration.getChannels()`，
hostapd 拿到的就是指定信道。

### 方法2：使用VPNHotspot
使用[VPNHotspot](https://github.com/Mygod/VPNHotspot)设置系统热点配置。

安卓12及以下：指定AP频段为特定信道。

安卓13+：指定频段为5G，ACS可选频段为信道，或指定AP频段为特定信道。

手机重启后可能需要手动指定。


## 关闭IPv6中继
### Hook点
`android.net.ip.IpServer` 的 `updateUpstreamIPv6LinkProperties`（安卓9 是
`com.android.server.connectivity.tethering.TetherInterfaceStateMachine` 的同名方法，安卓10 在
`frameworks/base/services/net/java/android/net/ip/IpServer.java`）：

| 版本 | 签名 |
|------|------|
| 9 / 10 | `private void updateUpstreamIPv6LinkProperties(LinkProperties v6only)` |
| 11 ~ 16 | `private void updateUpstreamIPv6LinkProperties(LinkProperties v6only, int ttlAdjustment)` |

方法名安卓9~16 没变过，只有参数个数不同，所以按名字找、把 `args[0]` 置成 `null` 即可，
不用管参数列表。调用方是 `IPv6TetheringCoordinator`（`CMD_IPV6_TETHER_UPDATE`），
它在上游 IPv6 掉线时发的本来就是 `null`。

改哪个共享方式由 `mInterfaceType` 决定（各版本都有这个字段，固定IP也在用它），
配置存在 `ipv6_relay_<type>` 里。

## 感谢
[@mmfmkuang](https://github.com/mmfmkuang)

[@dsfgdadg](https://github.com/dsfgdadg)
