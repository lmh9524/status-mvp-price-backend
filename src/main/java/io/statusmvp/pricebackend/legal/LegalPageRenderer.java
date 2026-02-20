package io.statusmvp.pricebackend.legal;

public final class LegalPageRenderer {
  private LegalPageRenderer() {}

  public static String renderTermsHtml(LegalProperties properties) {
    String appName = escapeHtml(nonEmpty(properties.getAppName(), "Veil Wallet"));
    String entityName = escapeHtml(nonEmpty(properties.getEntityName(), "VeilLabs"));
    String effectiveDate = escapeHtml(nonEmpty(properties.getEffectiveDate(), "2026-02-20"));
    String lawEn =
        escapeHtml(
            nonEmpty(
                properties.getGoverningLaw(), "Laws of the Hong Kong Special Administrative Region"));
    String lawZh = escapeHtml(nonEmpty(properties.getGoverningLawZh(), "香港特别行政区法律"));
    String email = escapeHtml(nonEmpty(properties.getContactEmail(), "veillabs.wallet@gmail.com"));
    String address = escapeHtml(nonEmpty(properties.getContactAddress(), "香港九龙区"));

    return """
        <!doctype html>
        <html lang="zh-CN">
          <head>
            <meta charset="utf-8" />
            <meta name="viewport" content="width=device-width, initial-scale=1" />
            <title>%s 服务协议 / Terms of Service</title>
            <style>
              body { margin: 0; background: #f7f8fa; color: #111827; font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", sans-serif; line-height: 1.65; }
              main { max-width: 920px; margin: 0 auto; padding: 24px 16px 40px; }
              h1 { font-size: 28px; margin: 0 0 8px; }
              h2 { font-size: 20px; margin: 24px 0 8px; }
              p, li { font-size: 15px; }
              .meta { color: #4b5563; font-size: 14px; margin-bottom: 20px; }
              section { background: #fff; border-radius: 12px; padding: 16px; margin-bottom: 12px; box-shadow: 0 1px 2px rgba(0,0,0,.04); }
              ul { margin: 6px 0 0 20px; padding: 0; }
            </style>
          </head>
          <body>
            <main>
              <h1>%s 服务协议 / Terms of Service</h1>
              <p class="meta">生效日期 / Effective Date: %s</p>
              <section>
                <p>本协议由 %s（“我们”）与您就 %s 及相关服务的使用达成。</p>
                <p>This agreement is entered into between %s ("we", "us") and you regarding your use of %s and related services.</p>
              </section>
              <section>
                <h2>1. 服务性质 / Service Nature</h2>
                <ul>
                  <li>%s 为非托管钱包工具，我们不持有您的私钥、助记词或链上资产。</li>
                  <li>%s is a non-custodial wallet tool. We do not hold your private keys, seed phrases, or on-chain assets.</li>
                  <li>链上交易一经确认通常不可撤销，您需自行承担操作结果。</li>
                  <li>On-chain transactions are generally irreversible once confirmed.</li>
                </ul>
              </section>
              <section>
                <h2>2. 用户责任 / User Responsibilities</h2>
                <ul>
                  <li>妥善保管私钥、助记词和设备，避免泄露。</li>
                  <li>在提交交易前核对网络、地址、金额、合约数据和签名阈值。</li>
                  <li>遵守适用法律法规，不得从事违法或侵权活动。</li>
                  <li>You must protect your credentials, verify transaction details, and comply with applicable laws.</li>
                </ul>
              </section>
              <section>
                <h2>3. 费用与第三方 / Fees and Third-Party Services</h2>
                <ul>
                  <li>区块链网络费（Gas）由网络收取，不由 %s 收取。</li>
                  <li>Gas/network fees are charged by the blockchain network, not by %s.</li>
                  <li>部分功能可能依赖第三方节点、行情、签名或消息服务，第三方可用性和政策变化可能影响体验。</li>
                </ul>
              </section>
              <section>
                <h2>4. 风险提示 / Risk Disclosure</h2>
                <ul>
                  <li>数字资产价格波动、智能合约漏洞、钓鱼、网络拥堵、链分叉等均可能导致损失。</li>
                  <li>Digital assets involve significant risks, including volatility, smart contract vulnerabilities, phishing, congestion, and forks.</li>
                  <li>我们不提供投资建议，您应独立判断并自行承担风险。</li>
                </ul>
              </section>
              <section>
                <h2>5. 责任限制 / Limitation of Liability</h2>
                <ul>
                  <li>在法律允许范围内，我们按“现状”提供服务，不保证持续可用、无中断或无错误。</li>
                  <li>To the maximum extent permitted by law, services are provided "as is" without warranties of uninterrupted availability.</li>
                  <li>因您设备、安全保管不当、第三方服务异常或区块链本身因素导致的损失，我们不承担间接、附带或惩罚性责任。</li>
                </ul>
              </section>
              <section>
                <h2>6. 协议变更 / Changes</h2>
                <p>我们可根据业务或合规要求更新本协议。更新后继续使用服务即视为您接受更新内容。</p>
                <p>We may update these terms. Continued use after updates constitutes acceptance.</p>
              </section>
              <section>
                <h2>7. 适用法律与争议 / Governing Law and Dispute Resolution</h2>
                <p>本协议受 %s 约束（English: %s）。</p>
                <p>因本协议产生的争议，应先友好协商；协商不成时，提交有管辖权的法院或仲裁机构处理。</p>
              </section>
              <section>
                <h2>8. 联系方式 / Contact</h2>
                <p>主体 / Entity: %s</p>
                <p>邮箱 / Email: <a href="mailto:%s">%s</a></p>
                <p>地址 / Address: %s</p>
              </section>
            </main>
          </body>
        </html>
        """
        .formatted(
            appName,
            appName,
            effectiveDate,
            entityName,
            appName,
            entityName,
            appName,
            appName,
            appName,
            appName,
            appName,
            lawZh,
            lawEn,
            entityName,
            email,
            email,
            address);
  }

  public static String renderPrivacyHtml(LegalProperties properties) {
    String appName = escapeHtml(nonEmpty(properties.getAppName(), "Veil Wallet"));
    String entityName = escapeHtml(nonEmpty(properties.getEntityName(), "VeilLabs"));
    String effectiveDate = escapeHtml(nonEmpty(properties.getEffectiveDate(), "2026-02-20"));
    String lawEn =
        escapeHtml(
            nonEmpty(
                properties.getGoverningLaw(), "Laws of the Hong Kong Special Administrative Region"));
    String lawZh = escapeHtml(nonEmpty(properties.getGoverningLawZh(), "香港特别行政区法律"));
    String email = escapeHtml(nonEmpty(properties.getContactEmail(), "veillabs.wallet@gmail.com"));
    String address = escapeHtml(nonEmpty(properties.getContactAddress(), "香港九龙区"));

    return """
        <!doctype html>
        <html lang="zh-CN">
          <head>
            <meta charset="utf-8" />
            <meta name="viewport" content="width=device-width, initial-scale=1" />
            <title>%s 隐私政策 / Privacy Policy</title>
            <style>
              body { margin: 0; background: #f7f8fa; color: #111827; font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", sans-serif; line-height: 1.65; }
              main { max-width: 920px; margin: 0 auto; padding: 24px 16px 40px; }
              h1 { font-size: 28px; margin: 0 0 8px; }
              h2 { font-size: 20px; margin: 24px 0 8px; }
              p, li { font-size: 15px; }
              .meta { color: #4b5563; font-size: 14px; margin-bottom: 20px; }
              section { background: #fff; border-radius: 12px; padding: 16px; margin-bottom: 12px; box-shadow: 0 1px 2px rgba(0,0,0,.04); }
              ul { margin: 6px 0 0 20px; padding: 0; }
            </style>
          </head>
          <body>
            <main>
              <h1>%s 隐私政策 / Privacy Policy</h1>
              <p class="meta">生效日期 / Effective Date: %s</p>
              <section>
                <p>%s（“我们”）重视您的隐私。本政策说明我们在提供 %s 服务过程中如何收集、使用、存储与保护信息。</p>
                <p>%s ("we", "us") values your privacy. This policy explains how we collect, use, store, and protect information when providing %s.</p>
              </section>
              <section>
                <h2>1. 我们可能收集的信息 / Information We May Collect</h2>
                <ul>
                  <li>链上公开信息：如钱包地址、交易哈希、交易时间等。</li>
                  <li>设备与网络信息：如设备标识、IP、日志、错误信息，用于安全和稳定性保障。</li>
                  <li>您主动提供的信息：如联系邮箱、反馈内容、客服沟通记录。</li>
                  <li>Public blockchain data, device/network logs, and information you submit to us.</li>
                </ul>
              </section>
              <section>
                <h2>2. 使用目的 / How We Use Data</h2>
                <ul>
                  <li>提供和维护钱包功能（如交易构建、状态查询、多签协作流程）。</li>
                  <li>风险控制、安全审计、故障排查和性能优化。</li>
                  <li>响应您的咨询、投诉和合规请求。</li>
                  <li>To provide services, maintain security, improve reliability, and support users.</li>
                </ul>
              </section>
              <section>
                <h2>3. 共享与披露 / Sharing and Disclosure</h2>
                <ul>
                  <li>我们不会出售您的个人信息。</li>
                  <li>We do not sell your personal information.</li>
                  <li>在实现功能所必需时，可能向云服务、消息推送、区块链节点等服务提供商共享最小必要数据。</li>
                  <li>法律法规或监管要求下，我们可能依法披露相关信息。</li>
                </ul>
              </section>
              <section>
                <h2>4. 保存与安全 / Retention and Security</h2>
                <ul>
                  <li>我们仅在实现业务目的或符合法律要求所需期限内保存信息。</li>
                  <li>我们采用合理安全措施保护数据，但无法保证任何系统绝对安全。</li>
                  <li>We retain data only as needed and apply reasonable security controls.</li>
                </ul>
              </section>
              <section>
                <h2>5. 您的权利 / Your Rights</h2>
                <ul>
                  <li>在适用法律范围内，您可请求访问、更正、删除或限制处理您的相关信息。</li>
                  <li>Where applicable, you may request access, correction, deletion, or restriction of your data.</li>
                </ul>
              </section>
              <section>
                <h2>6. 跨境与法域 / Cross-Border Processing and Jurisdiction</h2>
                <p>由于区块链和云基础设施的全球特性，相关信息可能在不同国家或地区处理。</p>
                <p>本政策受 %s 约束（English: %s）。</p>
              </section>
              <section>
                <h2>7. 政策更新 / Policy Updates</h2>
                <p>我们可能不时更新本政策。更新后继续使用服务，视为您同意更新内容。</p>
                <p>We may update this policy from time to time. Continued use means acceptance of the updated policy.</p>
              </section>
              <section>
                <h2>8. 联系方式 / Contact</h2>
                <p>主体 / Entity: %s</p>
                <p>邮箱 / Email: <a href="mailto:%s">%s</a></p>
                <p>地址 / Address: %s</p>
              </section>
            </main>
          </body>
        </html>
        """
        .formatted(
            appName,
            appName,
            effectiveDate,
            entityName,
            appName,
            entityName,
            appName,
            lawZh,
            lawEn,
            entityName,
            email,
            email,
            address);
  }

  private static String nonEmpty(String value, String fallback) {
    if (value == null) return fallback;
    String trimmed = value.trim();
    return trimmed.isEmpty() ? fallback : trimmed;
  }

  private static String escapeHtml(String input) {
    if (input == null) return "";
    return input
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&#39;");
  }
}
