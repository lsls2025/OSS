package com.aurora.chat.ui.onboarding

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aurora.chat.ui.theme.AppColors

/** 协议阅读的最短秒数。与"必须滑到底"共同构成同意前置条件。 */
internal const val POLICY_MIN_READ_SECONDS = 6

/** 协议版本号：条款有实质变更时必须同步 +1，否则已同意的用户不会被要求重新确认。 */
internal const val POLICY_VERSION = "2026.10.3"

/**
 * 隐私政策与服务协议正文（企业 / 商用级）。
 *
 * ## 组织原则
 * 按**商用法律文本的常规结构**组织（定义 → 服务范围 → 账号 → 用户义务 → 隐私收集 →
 * 存储与跨境 → 使用与共享 → AI 专项 → 知识产权 → 第三方 → 付费 → 未成年人 →
 * 数据安全与权利 → 免责与责任限制 → 违约与终止 → 变更与争议），章节之间具备逻辑递进。
 *
 * ## 覆盖范围（对应项目实际存在的功能与代码）
 * - 即时通讯：私聊 / 群聊 / 社区 / 漂流瓶 / 红包 / 小游戏（五子棋）/ 二维码扫描 —— 服务端数据库
 * - AI：本地对话、文件工作区、界面自动化操作、
 *   安装与卸载应用、安装包逆向分析、图片视频生成、联网检索
 * - 通知推送、公共存储与媒体访问、生物识别验证、定时任务
 * - 付费：官方额度、自带 API Key 直连、发票与退款规则
 * - 权限：位置、相机、麦克风、悬浮窗、通知、安装外部应用、所有文件访问
 *
 * ## 免责重点（这是本文件的核心目的）
 * 明确写出：用户内容责任、逆向与自动化操作的使用边界、AI 输出不构成专业建议、
 * 数据跨境流向、服务"按现状"提供、间接损失不承担、第三方服务行为不由本应用负责。
 */
@Composable
internal fun PolicyBody(appColors: AppColors, primary: Color) {
    PolicyHeaderCard(appColors, primary)
    Spacer(Modifier.height(18.dp))

    PolicySection(
        index = "一",
        title = "定义与解释",
        paragraphs = listOf(
            "**本应用**：指 Aurora Chat 安卓客户端及其配套的服务端系统、官方网站（example.com）与官方交流群所构成的整体服务。",
            "**我们 / 运营方**：指 Aurora Chat 的运营主体。**你 / 用户**：指按本协议完成注册或使用本应用的自然人及依法登记的法人或其他组织。",
            "**用户内容**：指你通过本应用提交、传输、生成或存储的一切信息与成果，包括文字、图片、音视频、文件、指令、代码及 AI 的执行产物。",
            "**服务**：指本应用提供的即时通讯、社区互动、AI 对话与 AI 执行、文件工作区、付费能力及配套功能。",
            "本协议是你与我们之间就使用本应用达成的完整约定。你开始使用本应用即视为已阅读并同意本协议；若不同意，请点击「拒绝并退出」并停止使用。"
        )
    )

    PolicySection(
        index = "二",
        title = "服务内容与功能范围",
        paragraphs = listOf(
            "**基础服务**：即时通讯（私聊、群聊、社区与动态）、漂流瓶、红包与转账、二维码扫描、小游戏（如五子棋）及对战数据。上述内容的存储与同步规则见第六条。",
            "**AI 服务**：AI 对话、AI 自动化执行（读写文件、运行命令与脚本、安装或卸载应用、界面自动化操作）、图片与视频生成、联网检索。",
            "**文件与工作区**：本应用可在你授权范围内访问公共存储中的文件，并提供独立工作区用于 AI 任务的输入、中间产物与输出。",
            "**付费服务**：官方 AI 额度、会员或增值服务，以及自带 API Key 的直连接入。具体规则见第十一条。",
            "服务的具体形态、界面与功能范围以你当前安装版本的实际表现为准；我们可能适时新增、调整或下线部分功能，并尽量以合理方式通知。"
        )
    )

    PolicySection(
        index = "三",
        title = "账号注册、使用与安全",
        paragraphs = listOf(
            "使用本应用需要注册账号。你应保证注册信息真实、准确、可用，并同意我们对账号进行实名与安全风控核验。账号仅限你本人或你代表的主体使用，不得转让、出租、出借或共享。",
            "你应对账号下的全部行为负责，包括使用 AI 服务时提交的指令与生成结果。企业或组织账号的使用者，应确保其行为已获得所在组织的授权。",
            "你应妥善保管账号凭证与登录设备。因保管不当、主动泄露、设备被他人使用或被恶意软件窃取导致的损失，由你自行承担；发现账号异常或疑似被盗，应立即修改凭证并通过第八条、第十六条的联系方式告知我们。",
            "我们可能基于安全风控对异常登录、异地登录、异常调用频次采取限制或要求额外验证；情节严重时，有权依第十五条处理。"
        )
    )

    PolicySection(
        index = "四",
        title = "用户内容与行为规范",
        paragraphs = listOf(
            "你对你提交的全部用户内容负责，并保证你对相关内容拥有合法权利或已获得充分授权。你不得利用本应用制作、复制、发布、传播违法违规信息，不得侵犯他人知识产权、隐私权、名誉权等合法权益，不得实施欺诈、骚扰、恶意营销、传播恶意程序或进行任何危害网络与数据安全的行为。",
            "**安装包逆向分析**：该能力仅可用于你自己的软件、已获权利人明确授权的软件，或法律法规允许的安全研究与教学场景。你不得将其用于破解他人软件、绕过技术保护措施、制作或传播破解版本、窃取他人数据。由此产生的法律责任由你自行承担。",
            "**自动化执行**：本应用允许你通过指令执行命令与脚本、读写文件、安装或卸载应用、操作界面。你应确保指令与目标合法、可控、可追溯；因指令本身、目标对象或执行结果引发的纠纷与损失，由你自行承担。",
            "**小游戏公平性**：参与小游戏对战或排行榜时，你不得使用外挂、脚本、漏洞刷分或任何干扰对局公平性的手段。",
            "**二维码扫描**：扫码仅用于识别链接、文本或商品信息等公开内容。你不得利用扫码功能诱导他人访问恶意链接、传播虚假信息或窃取他人信息。",
            "我们有权对违反上述规范的内容与账号采取删除、限制功能、暂停或终止服务等措施，并依法保存与移交相关记录。"
        )
    )

    PolicySection(
        index = "五",
        title = "隐私保护：我们收集的信息",
        paragraphs = listOf(
            "**账号信息**：注册所需的邮箱、QQ 号或第三方授权标识、昵称、头像，以及你主动填写的资料。",
            "**通讯内容**：你发送与接收的消息、群组与社区内容、漂流瓶内容、红包与转账记录。这类信息为提供服务所必需，会被存储（见第六条）。",
            "**设备与运行信息**：设备型号、系统版本、应用版本、崩溃与错误日志、网络状态。用于兼容性适配、故障定位与安全防护。",
            "**AI 交互信息**：你的指令、上下文、AI 的工具调用记录与工作区文件。这部分默认只保存在你的设备本地（见第六条），仅在必要时按第八条所述发送至模型服务商以生成回复。",
            "**权限相关信息**：在你授权后，位置用于「附近的人」与位置共享；相机与麦克风仅在你主动发起拍照、录像、语音时调用；悬浮窗用于后台任务进度提示；安装外部应用权限用于让 AI 拉起系统安装器；所有文件访问权限用于按你的指令读写公共存储中的文件。未授权的权限不会被读取。",
            "**通知与推送**：通知权限仅用于向你的设备推送新消息提醒与任务完成提醒。推送内容可能包含消息预览（例如发送者与消息摘要）；你可以在系统设置中关闭通知或关闭预览。",
            "**生物识别验证**：部分敏感操作可能调用系统生物识别（指纹、面部）进行二次验证。生物特征数据由系统在本机安全区域处理，本应用不采集、不存储你的任何生物特征。",
            "**设备状态信息**：为维持后台消息接收与定时任务，本应用可能请求精确闹钟等系统能力；为统计与故障定位，可能读取设备标识、WiFi 状态与电话状态（仅设备级信息，不涉及通话内容）。",
            "除本协议明确说明的情形外，我们不在你不知情的情况下收集与服务无关的信息，并始终遵循最小必要原则。"
        )
    )

    PolicySection(
        index = "六",
        title = "信息存储、跨境传输与期限",
        paragraphs = listOf(
            "**服务端存储**：账号信息、通讯内容、社区与群组数据、红包与交易记录保存在我们的服务器数据库中，与账号绑定。因此卸载应用、清除本地数据或更换设备**不会**删除这些数据，重新登录后可恢复。",
            "**媒体文件的保留**：为控制存储成本，超过一定期限（当前为 30 天）的聊天图片与视频会清理**物理文件**；消息记录本身不在此清理范围内。清理后的媒体无法再查看或下载。",
            "**本地存储**：AI 对话、AI 的工作区文件、执行日志、缓存保存在你的设备本地（应用私有目录或你指定的公共存储位置，如「工作区」目录）。这类数据不上传服务器，因此换设备后不可恢复；卸载应用或清除应用数据会永久删除且无法找回。",
            "**跨境传输**：使用本应用提供的官方额度时，你的对话上下文与必要指令会发送至相应的第三方模型服务商以生成回复；该服务商可能位于境外，数据传输可能构成跨境传输。若你在「API 接入」中填写了自己的密钥，请求由你的密钥直接发起，我们不居中转存，数据流向由对应服务商的政策决定。",
            "**期限**：账号存续期间我们持续保存服务端数据。账号注销后，除法律法规要求保留的记录外，按第十四条处理。"
        )
    )

    PolicySection(
        index = "七",
        title = "信息的使用与共享",
        paragraphs = listOf(
            "**使用目的**：实现功能本身（消息投递与同步、群组与社区管理、红包与交易结算、AI 对话与 AI 执行、文件与工作区操作）；开展安全与合规工作（识别垃圾信息、欺诈、攻击行为，处置违规内容，响应有权机关的合法要求）；改进服务（以去标识化或汇总方式分析功能使用情况与故障分布）。我们不会将你的通讯内容用于广告投放。",
            "**共享情形**：为使 AI 服务正常运行，你的对话上下文与指令会按第八条所述共享给模型服务商；为提供存储、传输与推送，可能使用第三方云服务与推送通道；除上述情形及法律法规要求外，我们不会向第三方出售或提供你的个人信息。",
            "**去标识化使用**：我们可能将匿名化、去标识化的数据用于服务优化、模型评测与安全研究，此类数据无法再识别到你个人。"
        )
    )

    PolicySection(
        index = "八",
        title = "AI 助手与自动化操作专项条款",
        paragraphs = listOf(
            "**输出性质**：AI 生成内容由模型自动产生，可能不准确、不完整或不符合预期。它不构成法律、医疗、财务等专业建议，不应作为重要决策的唯一依据。AI 输出不代表我们的观点。",
            "**执行风险**：AI 的执行结果依赖模型判断与运行环境，可能失败或产生非预期改动。本应用为此提供前置备份、逐行差异对比与撤销能力，但你仍应在执行后自行核验结果，尤其涉及删除文件、批量改写、执行系统命令等不可逆操作时。",
            "**界面自动化操作**：你可在系统已授权的范围内，通过指令让 AI 协助完成界面操作。该能力可能涉及第三方应用界面，因目标应用本身、你的指令或系统环境导致的异常与损失，由你自行承担。",
            "**执行日志**：为便于追溯，工具调用与文件改动会逐条写入本地执行日志（即便进程被强制结束）。该日志在本机保存，用于你自查，不会上传。",
            "**生成内容合规**：AI 生成的文字、图片、代码与应用可能与他人在先作品相似，其可版权性、可商用性与传播合规由你自行评估；将其用于商业用途前，你应独立判断侵权风险。",
            "**生成内容标识**：依照生成式人工智能相关法规要求，本应用对 AI 生成的图片、音视频等内容在可识别的场景进行标识或提示。你转发、再加工或对外发布 AI 生成内容时，不得移除相关标识，也不得冒充真人创作。",
            "**禁止用途**：你不得利用本应用生成违法信息或侵害他人权益的内容，不得将 AI 能力用于绕过安全措施、规模化伪造信息、实施网络攻击或任何违反法律法规的用途。"
        )
    )

    PolicySection(
        index = "九",
        title = "知识产权",
        paragraphs = listOf(
            "本应用的软件、界面、商标、文档、AI 相关技术方案及其中的知识产权归运营方所有。未经许可，你不得反向工程、复制、分发或用于商业目的（法律法规允许的情形除外）。",
            "你保留对自己用户内容的全部权利。为向你提供服务所必需，你授权我们在必要范围内存储、传输与处理这些内容；该授权不改变内容的所有权归属，且不授权我们将其用于本协议之外的商业用途。",
            "你使用第三方素材（字体、图片、代码库、开源组件等）时，应自行确保获得相应许可并遵守其许可条款。",
            "任何因你的用户内容或使用行为引发的第三方权利主张，由你自行负责处理并承担相应责任。"
        )
    )

    PolicySection(
        index = "十",
        title = "第三方服务与外部链接",
        paragraphs = listOf(
            "**模型服务**：使用官方额度时，第三方模型服务商依据其自身政策处理你的请求数据；若你使用自带 API Key，计费、稳定性与数据流向均由对应服务商的政策决定。我们不对任何第三方服务商的可用性、回复内容与数据处理行为负责。",
            "**联网工具**：搜索、网页抓取、文件下载等能力会访问公开网络，目标站点的数据处理行为由其自身政策约束。",
            "**基础设施**：为提供存储、传输与安全防护，可能使用第三方云服务与推送通道，其运营方按各自政策与合同约定处理数据。",
            "**外部链接**：本应用内可能包含官网（example.com）与官方交流群等跳转入口。离开本应用后的访问行为，适用目标平台自身的规则与政策。"
        )
    )

    PolicySection(
        index = "十一",
        title = "付费服务与虚拟商品",
        paragraphs = listOf(
            "**官方额度与增值服务**：本应用提供官方 AI 额度、会员或增值服务等付费项目。购买前请确认价格、数量与有效期；支付完成后额度即时到账，虚拟商品一经消耗或使用即不支持无理由退款，法律法规另有规定的除外。",
            "**自带 API Key**：你可在「API 接入」中配置自己的模型服务密钥。该密钥由你自行保管，仅用于直连上游服务商；相关计费、限额与合规责任由你与对应服务商自行处理。",
            "**付费主体**：请使用你本人或监护人有权支配的支付账户购买。未成年人未经监护人同意进行的购买，监护人可依法主张相关权利。",
            "**价格与发票**：价格以购买时的页面展示为准；发票需求可通过应用内反馈渠道申请，我们按税务规定开具。",
            "**额度有效期与失效**：额度有效期以购买页面说明为准，到期未使用部分自动失效，不折算、不退还；因账号违规被限制或注销的，未使用额度不予退还。"
        )
    )

    PolicySection(
        index = "十二",
        title = "未成年人保护",
        paragraphs = listOf(
            "本应用主要面向成年人提供服务。**未满 14 周岁的未成年人不得注册和使用本应用**；已满 14 周岁未满 18 周岁的未成年人，应在监护人阅读并同意本协议后使用。",
            "监护人应引导未成年人合理使用网络，监督其不发布个人隐私、不向陌生人透露身份信息、不进行非理性消费，并关注 AI 生成内容的潜在影响。",
            "若我们发现用户为未满 14 周岁的未成年人，将依法限制或终止其账号及相关服务。"
        )
    )

    PolicySection(
        index = "十三",
        title = "数据安全与用户权利",
        paragraphs = listOf(
            "我们采取传输加密、访问控制、日志审计、最小权限等合理安全措施保护你的数据；但任何安全措施都无法保证绝对安全，你应妥善保管账号凭证并对设备安全负责。",
            "**你的权利**：你可以访问、更正、删除你的账号资料与服务端消息；可以随时撤回已授予的位置、相机、麦克风、通知、所有文件访问等权限；可以拒绝 AI 对公共存储的访问。撤回或拒绝不影响此前基于授权的处理。",
            "你可以通过应用内「我 → 关于我们」的官方交流群提出隐私咨询、投诉或行使上述权利，我们将在合理期限内处理并答复；涉及法律法规要求的个人数据请求，我们将按适用法律响应。"
        )
    )

    PolicySection(
        index = "十四",
        title = "免责声明与责任限制",
        paragraphs = listOf(
            "本应用按**现状**提供。我们会尽力维持可用性，但不保证服务不中断、不出错、数据不丢失。因系统维护、升级、网络故障、设备问题、不可抗力或第三方服务异常导致的中断与损失，我们在法律允许范围内不承担责任。",
            "**重要数据请自行备份**。本地数据（AI 对话、工作区文件）在卸载、清除数据或设备损坏后无法恢复；服务端媒体文件超过保留期限会被清理。",
            "**特殊运行环境免责**：若设备已获取 Root、解锁 Bootloader、刷入非官方系统或第三方内核、运行于模拟器或非官方环境，或安装第三方插件、修改本应用文件，本应用不保证正常运作，由此导致的故障、数据丢失、账户异常或系统不稳定，我们在法律允许范围内不承担责任。",
            "**AI 输出免责**：除法律法规另有规定外，我们对 AI 生成内容的不准确、不完整或不符合预期不承担责任；你对基于该内容作出的决策与行为负全部责任。",
            "在法律允许的最大范围内，我们不对间接损失、可得利益损失、数据损失、商誉损失承担责任；对我们的全部责任，以你在争议发生前十二个月内为本应用实际支付的费用总额为限。",
            "本条款不排除或限制依法不得排除或限制的责任。"
        )
    )

    PolicySection(
        index = "十五",
        title = "违规认定、处罚与协议终止",
        paragraphs = listOf(
            "**违规情形**：下列行为构成违规，我们有权依情节处理——发布或传播违法违规信息；侵犯他人知识产权、隐私权、名誉权等合法权益；实施欺诈、骚扰、恶意营销或传播恶意程序；将安装包逆向能力用于破解他人软件、绕过技术保护措施或窃取数据；滥用自动化执行能力实施侵权或非法行为；使用外挂、脚本、漏洞刷分或干扰小游戏对局公平；诱导他人扫码访问恶意链接或窃取信息；批量注册、养号或转租出借账号；恶意规避安全风控、攻击服务或滥用接口；冒充官方、他人身份或移除 AI 生成内容标识冒充真人创作。",
            "**处罚梯度**：轻微违规（警告、删除违规内容、短期禁言 1 至 7 天）；较重违规（临时封禁 7 至 30 天、限制部分功能）；严重违规或屡次违规（永久封禁、注销账号）；涉嫌违法犯罪的，我们依法保存相关记录并移交有权机关。处罚时我们会综合违规性质、主观过错、影响范围与是否初犯进行裁量。",
            "**不构成违规的边界**：正常聊天与社区互动；使用官方功能及自带 API Key 正常直连；AI 输出不准确、不完整或不符合预期本身不属于违规，但利用其从事违法用途除外；删除、导出你自己的消息、文件与工作区数据；对服务处理结果有异议并依法申诉。",
            "**申诉**：如对违规认定或处罚有异议，可在收到处理结果后通过应用内「我 → 关于我们」的官方交流群提出申诉，我们将在合理期限内复核并答复。",
            "账号被终止或注销后，除法律法规要求保留的记录外，账号数据将按流程删除或匿名化，且不可恢复；未使用的付费额度不予退还。",
            "我们因经营调整、服务下线或合规要求终止某项服务时，将提前以合理方式通知，并依法处理你的数据与未消耗权益。"
        )
    )

    PolicySection(
        index = "十六",
        title = "协议变更、法律适用与争议解决",
        paragraphs = listOf(
            "条款发生实质性变更时，我们会更新协议版本号，并在你下次启动时重新展示本页、要求重新确认。版本号不会静默变更；你在变更生效后继续使用本应用，视为同意更新后的协议。",
            "本协议的订立、效力、解释与争议解决均适用中华人民共和国法律。",
            "因本协议产生的争议，双方应先行协商解决；协商不成的，任何一方均可提交运营方所在地有管辖权的人民法院解决。",
            "当前版本：$POLICY_VERSION。如对本协议有任何疑问、建议或投诉，可通过应用内「我 → 关于我们」的官方交流群联系我们，我们将在合理期限内回复。"
        )
    )
}

@Composable
private fun PolicyHeaderCard(appColors: AppColors, primary: Color) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(appColors.cardBg)
            .padding(14.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "Aurora Chat",
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(Modifier.weight(1f))
            Text(
                text = POLICY_VERSION,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                color = primary
            )
        }
        Spacer(Modifier.height(6.dp))
        Text(
            text = "共十六章。涉及数据存储与跨境传输、第三方共享、AI 与自动化操作边界、免责与责任限制、付费规则的条款已用粗体标出，请重点阅读第六条至第八条、第十一条、第十四条。",
            fontSize = 12.sp,
            lineHeight = 18.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun PolicySection(index: String, title: String, paragraphs: List<String>) {
    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(width = 34.dp, height = 24.dp)
                    .clip(RoundedCornerShape(7.dp))
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = index,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            Spacer(Modifier.width(8.dp))
            Text(
                text = title,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
        Spacer(Modifier.height(9.dp))
        paragraphs.forEach { p ->
            // 用 **包裹的片段改为加粗呈现，而不是把星号直接印出来
            PolicyParagraph(p)
            Spacer(Modifier.height(9.dp))
        }
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun PolicyParagraph(text: String) {
    val parts = text.split("**")
    if (parts.size <= 1) {
        Text(
            text = text,
            fontSize = 13.sp,
            lineHeight = 21.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        return
    }
    androidx.compose.foundation.text.BasicText(
        text = androidx.compose.ui.text.buildAnnotatedString {
            parts.forEachIndexed { i, seg ->
                if (seg.isEmpty()) return@forEachIndexed
                if (i % 2 == 1) {
                    withStyle(androidx.compose.ui.text.SpanStyle(fontWeight = FontWeight.SemiBold)) {
                        append(seg)
                    }
                } else {
                    append(seg)
                }
            }
        },
        style = androidx.compose.ui.text.TextStyle(
            fontSize = 13.sp,
            lineHeight = 21.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    )
}
