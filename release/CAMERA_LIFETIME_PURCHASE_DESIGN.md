# Tovi Camera 相机购买方案

日期：2026-10-11。状态：0.17.8 / 26 已实现两个渠道的五商品购买和永久权益。自动化测试与实机商店验收分别记录；后台商品和测试账号由用户确认已配置，实机验收由用户后续执行。

## 商品与定价

三星包名：`com.ycolor.team.phytoy.camera.android.galaxyapp`。

| 英文商品名 | 商品 ID | 美元基准价 | 永久权益 |
| --- | --- | ---: | --- |
| DIGITAL 01 Camera Unlock | `camera_digital_01` | $5.99 | 仅 DIGITAL 01 |
| PLASTIC 82 Camera Unlock | `camera_plastic_82` | $5.99 | 仅 PLASTIC 82 |
| STREET 84 Camera Unlock | `camera_street_84` | $5.99 | 仅 STREET 84 |
| FISHEYE 05 Camera Unlock | `camera_fisheye_05` | $5.99 | 仅 FISHEYE 05 |
| All Cameras Lifetime Pass | `camera_all_lifetime` | $19.99 | 当前和未来新增的全部相机风格 |

前四个 ID 与当前代码一致。第五个也已写入代码，后台 ID 必须完全一致。DH 2++ COLOR、DH 2++ MONO 继续免费，无需商品。

三星商品类型统一使用 Item，一次购买后确认发货，不消耗、不订阅、不重复收费。后台配置美元基准价和各地区售价；应用只显示商店返回的本地货币价格，不能硬编码美元价格作为回退。

当前四款单买合计 $23.96，全相机权益价格 $19.99，相差 $3.97。全相机权益还包含后续新增相机。首版不显示固定“省 17%”标签；若以后增加节省金额，必须按当前地区实际同币种价格计算，缺少任何价格时不显示。

## 权益规则

- 单款购买永久解锁对应相机，不包含未来新增相机。
- 全相机购买永久解锁所有现有相机及后续在本应用新增的相机风格。新增相机随应用更新提供，用户无需再购买。
- 全相机购买记录作为独立权益保存，不能仅保存当前已解锁相机列表。四款分别购买不自动等于全相机永久权益。
- 全相机退款或撤销后，仅撤销全相机权益；独立购买的单款仍保留。单款被撤销时，如仍拥有全相机权益，该相机仍可使用。
- 首版全相机商品维持固定售价。已购单款不自动抵扣全相机价格；升级购买前明确显示这一点。差价升级需要另行设计，不通过修改收据或本地价格实现。
- 同一市场、同一商店账号可恢复有效购买。当前没有跨市场账号体系，Google Play 与三星购买不互通。

## 购买页面

沿用当前设计稿的黑色背景、黄色强调色、圆角卡片和相机主图。继续使用现有详情页，不插入新介绍流程。主图与简短介绍之后展示两张选择卡，示例价格仅表示美元地区后台设置完成后的效果。

```text
购买选项

[选中] 全部相机 · 永久解锁             $19.99
       当前全部相机 + 未来新增相机
       一次购买，无订阅

[未选] 仅解锁 DIGITAL 01               $5.99
       永久使用这款相机
       不包含其他及未来新增相机

免费预览                         恢复购买

底部黄色按钮：解锁全部相机 · $19.99
```

选择卡放在页面滚动内容内；底部只固定一个主购买按钮，保留当前大字号与小屏可滚动布局。默认选中全相机方案，两种选择的名称与价格都清晰可见。点击选择卡只切换选择，点击购买按钮才打开商店确认页。

选择单款后，按钮改为“解锁 DIGITAL 01 · 商店价格”，其说明改为仅此相机永久使用。全相机说明始终写明包含未来新增相机，不使用容易被理解成仅当前四款的“4 款相机包”。

从免费相机详情或设置入口进入时，仅展示全相机购买方案，不出现免费相机付费按钮。已拥有当前单款时，展示“这款相机已解锁”，仍可主动选择升级全相机权益；不再提供该单款的重复购买按钮。

已拥有全相机权益后，所有相机卡片移除锁标，详情页主要操作变为“使用这款相机”，设置页展示“全部相机已永久解锁 · 包含未来新增相机”。不再显示任何相机购买按钮。

付费风格继续支持免费实时预览。退出详情页、免费预览和系统返回始终可用。购买成功后保留用户当前选择的相机，不强制切换到另一款。

## 支付状态与恢复

每个商品独立处理价格可用性。例如全相机商品未配置时，已有正常价格的单款仍可购买；未取得价格的方案显示“暂不可购买”，提供重试，不显示虚构价格。

提交购买时锁定商品 ID 和所选方案，禁止第二笔并发购买。等待商店确认时显示“等待付款确认”；订单 pending 时不解锁。全相机 pending 时也不再发起重叠的单款购买。取消交易返回原选择，不弹出失败警告。

购买权益在收据校验、确认发货、可靠保存完成后授予；不能仅凭商店成功回调或本地布尔值解锁。重复回调和重试必须幂等。已校验缓存支持离线使用；网络失败不能自行撤销权益，商店权威查询确认撤销时才移除对应权益。

恢复购买同时查询五个商品。全相机恢复成功明确显示“已恢复全部相机永久权益”，单款恢复显示实际恢复的相机。重装后在同市场同账号下恢复，新增相机根据全相机权益自动解锁。

## 实现范围

商品、购买方法和完成事件已改用独立商品 ID，兼容原有四个单品 ID 与购买缓存；全相机包独立于当前 CameraStyle 列表。

StyleProducts 加入全相机商品，商品查询列表包含五个 ID。BillingSnapshot、购买接口、完成事件和 pending 状态支持全相机商品，并统一根据有效商品权益计算可用相机：免费相机、该单款权益、全相机权益满足其一即可。

GalaxyBillingController 的查询、购买、收据校验、确认、缓存、恢复和撤销路径均支持新商品。MainActivity、CameraPurchaseOverlay、SettingsOverlay 同步商品选择和已购状态。后续新增 CameraStyle 不需要给全相机用户补发每款订单。

PlayBillingController 已支持相同五商品方案，应在 Play 独立后台激活对应商品与 unlock BUY 选项并配置公钥；三星收据不能用于授予 Play 权益。拍照、预览渲染与相机引擎保持现有逻辑。

## 海外文案

全相机商品名：All Cameras Lifetime Pass。

全相机商品描述：Unlock all current and future camera styles in Tovi Camera with a single purchase. No subscription.

单款商品描述模板：Permanently unlock the DIGITAL 01 camera style. Other and future camera styles are not included.

详情主说明：All current and future cameras. Pay once. No subscription.

已购单款升级提示：Previous individual camera purchases are not deducted from the Lifetime Pass price.

上架描述购买段落替换为：DH 2++ COLOR and DH 2++ MONO are free. Additional camera styles can be unlocked individually through one-time purchases. The optional All Cameras Lifetime Pass unlocks all current and future camera styles in Tovi Camera with a single purchase. No subscription is required.

## 验收

1. 四个原商品正常购买、恢复，并继续永久解锁原相机。
2. 全相机购买与恢复后所有现有相机可拍摄保存；新增一个测试相机后也自动可用。
3. 四个单款全部拥有时，未来相机仍需单买或全相机权益，界面不误报已拥有全相机权益。
4. 撤销全相机后保留有效单款；撤销单款且仍有全相机权益时继续可用。
5. pending、取消、网络中断、重复回调、确认失败与保存失败不错误解锁或发起并发交易。
6. 价格加载失败不显示硬编码金额；单款与全相机商品可用性相互独立。
7. 更新兼容现有单款缓存；有效缓存支持离线；重装、账号切换与权威撤销均按商店记录恢复。
8. 单款与全相机方案、已购提示和升级提示在 200% 字号、小屏和 TalkBack 下完整可读；主按钮价格对应所选商品。
9. 单元测试覆盖权益组合、未来相机和撤销；三星实机覆盖真实商店订单与恢复。实际收费前核对商店付款单的金额和测试身份；许可证测试与实付测试分别记录。

参考：[Samsung IAP 商品与永久权益](https://developer.samsung.com/iap/programming-guide.html?lang=en)、[创建商品与地区定价](https://developer.samsung.com/iap/seller-portal-features/add-in-app-product.html)。
