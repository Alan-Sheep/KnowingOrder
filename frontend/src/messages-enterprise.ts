/**
 * 企业工作区（知识空间／企业问答／员工事务／组织成员／文档管理）的界面词条。
 *
 * 与 messages.ts 同一套结构：键是中文原文，值依次是 en / fr / ja / de / es。
 * 单独成文件是因为这部分的条目数远超公开侧，混在一个文件里难以定位。
 *
 * 约定：
 * 1. 键必须与模板里的字面量逐字一致（含全角标点与省略号），否则查不到会静默回退中文；
 * 2. 只翻译界面文案，不翻译用户输入、资料原文与模型回答；
 * 3. 占位符统一用 {name}，由 translate() 做替换。
 */
export type MessageRow = readonly [string, string, string, string, string];

export const enterpriseMessages: Record<string, MessageRow> = {
  // ── 通用 ────────────────────────────────────────────────
  '重试': ['Retry', 'Réessayer', '再試行', 'Erneut versuchen', 'Reintentar'],
  '重新加载{label}': ['Reload {label}', 'Recharger {label}', '{label}を再読み込み', '{label} neu laden', 'Recargar {label}'],
  '查看参考来源 {number}': ['View source {number}', 'Voir la source {number}', '出典 {number} を見る', 'Quelle {number} ansehen', 'Ver fuente {number}'],

  // ── 知识反馈处理 ────────────────────────────────────────
  '知识反馈处理 · 维护者': ['Knowledge feedback · maintainers', 'Retours de connaissance · mainteneurs', 'ナレッジフィードバック・管理者', 'Wissensfeedback · Maintainer', 'Comentarios de conocimiento · mantenedores'],
  '读取待办与处理记录': ['Load pending items and history', 'Charger les éléments en attente et l’historique', '未処理と履歴を読み込む', 'Offene Punkte und Verlauf laden', 'Cargar pendientes e historial'],
  '关联问题：': ['Related question: ', 'Question liée : ', '関連する質問：', 'Zugehörige Frage: ', 'Pregunta relacionada: '],
  '关联任务': ['Related job', 'Tâche liée', '関連ジョブ', 'Zugehöriger Job', 'Tarea relacionada'],
  '· 片段': ['· chunk', '· fragment', '・断片', '· Abschnitt', '· fragmento'],
  '处理说明：': ['Resolution: ', 'Traitement : ', '対応内容：', 'Bearbeitung: ', 'Resolución: '],
  '核验与处理说明': ['Verification and resolution note', 'Vérification et traitement', '確認と対応内容', 'Prüfung und Bearbeitung', 'Verificación y resolución'],
  '标记已处理': ['Mark resolved', 'Marquer comme traité', '処理済みにする', 'Als erledigt markieren', 'Marcar como resuelto'],
  '说明后关闭': ['Dismiss with note', 'Clôturer avec note', '説明して閉じる', 'Mit Notiz schließen', 'Cerrar con nota'],

  // ── 账号口令 ────────────────────────────────────────────
  '修改本人登录口令': ['Change your sign-in password', 'Modifier votre mot de passe', 'ログインパスワードの変更', 'Eigenes Kennwort ändern', 'Cambiar tu contraseña'],
  '修改登录口令': ['Change sign-in password', 'Modifier le mot de passe', 'ログインパスワード変更', 'Kennwort ändern', 'Cambiar la contraseña'],
  '账号安全': ['Account security', 'Sécurité du compte', 'アカウントの安全', 'Kontosicherheit', 'Seguridad de la cuenta'],
  '原口令': ['Current password', 'Mot de passe actuel', '現在のパスワード', 'Aktuelles Kennwort', 'Contraseña actual'],
  '新口令': ['New password', 'Nouveau mot de passe', '新しいパスワード', 'Neues Kennwort', 'Nueva contraseña'],
  '再次输入新口令': ['Repeat new password', 'Confirmer le nouveau mot de passe', '新しいパスワード（再入力）', 'Neues Kennwort wiederholen', 'Repite la contraseña nueva'],
  '正在修改…': ['Updating…', 'Modification…', '変更中…', 'Wird geändert…', 'Actualizando…'],
  '确认修改并退出登录': ['Save and sign out', 'Enregistrer et se déconnecter', '変更してログアウト', 'Speichern und abmelden', 'Guardar y cerrar sesión'],
  '不保存或回显口令；请求结果未知时先用新口令重新登录核对，不重复提交。': ['The password is never stored or echoed. If the result is unclear, sign in again with the new password instead of resubmitting.', 'Le mot de passe n’est ni conservé ni réaffiché. En cas de doute, reconnectez-vous avec le nouveau mot de passe plutôt que de renvoyer la demande.', 'パスワードは保存も再表示もしません。結果が不明なときは再送信せず、新しいパスワードで再ログインして確認してください。', 'Das Kennwort wird weder gespeichert noch angezeigt. Bei unklarem Ergebnis bitte mit dem neuen Kennwort neu anmelden statt erneut senden.', 'La contraseña no se guarda ni se muestra. Si el resultado no está claro, vuelve a iniciar sesión con la nueva contraseña en lugar de reenviar.'],

  // ── 商品查询 ────────────────────────────────────────────
  '商品查询演示': ['Product search demo', 'Démo de recherche de produits', '商品検索デモ', 'Produktsuche-Demo', 'Demo de búsqueda de productos'],
  'MOCK · 模拟商品': ['MOCK · sample products', 'MOCK · produits fictifs', 'MOCK・サンプル商品', 'MOCK · Beispielprodukte', 'MOCK · productos simulados'],
  '找点想吃的、想喝的': ['Find something to eat or drink', 'Trouver à manger ou à boire', '食べたいもの、飲みたいもの', 'Etwas zu essen oder zu trinken finden', 'Encuentra qué comer o beber'],
  '开发演示：商品、门店与价格均为虚构。跳转会打开淘宝搜索页并自动填入商品名，但示例商品本身是虚构的，搜索结果与报价由平台决定，不对应这里的示例商品。': ['Development demo: products, shops and prices are fictional. The link opens a Taobao search page with the product name filled in, but the sample product itself does not exist; results and prices are decided by the platform and do not correspond to these samples.', 'Démo de développement : produits, boutiques et prix sont fictifs. Le lien ouvre une recherche Taobao préremplie avec le nom du produit, mais l’article présenté n’existe pas ; les résultats et prix sont décidés par la plateforme.', '開発用デモです。商品・店舗・価格はすべて架空です。リンクは商品名を入れた淘宝の検索ページを開きますが、サンプル商品自体は実在せず、検索結果と価格はプラットフォーム側が決めます。', 'Entwicklungsdemo: Produkte, Geschäfte und Preise sind fiktiv. Der Link öffnet eine Taobao-Suche mit eingetragenem Produktnamen; das Beispielprodukt selbst existiert nicht, Ergebnisse und Preise bestimmt die Plattform.', 'Demo de desarrollo: productos, tiendas y precios son ficticios. El enlace abre una búsqueda de Taobao con el nombre del producto escrito, pero el producto de ejemplo no existe; los resultados y precios los decide la plataforma.'],
  '商品关键词': ['Product keyword', 'Mot-clé produit', '商品キーワード', 'Produktschlüsselwort', 'Palabra clave del producto'],
  '试试：咖啡、奶茶、晚餐': ['Try: coffee, milk tea, dinner', 'Essayez : café, thé au lait, dîner', '例：コーヒー、ミルクティー、夕食', 'Versuchen Sie: Kaffee, Milchtee, Abendessen', 'Prueba: café, té con leche, cena'],
  '查询中…': ['Searching…', 'Recherche…', '検索中…', 'Suche läuft…', 'Buscando…'],
  '查询示例商品': ['Search sample products', 'Rechercher des produits fictifs', 'サンプル商品を検索', 'Beispielprodukte suchen', 'Buscar productos de ejemplo'],
  '正在查询，请稍候；最多等待10秒。': ['Searching, please wait; up to 10 seconds.', 'Recherche en cours ; 10 secondes maximum.', '検索中です。最大10秒お待ちください。', 'Suche läuft, bitte warten; maximal 10 Sekunden.', 'Buscando; espere hasta 10 segundos.'],
  '没有匹配的模拟商品。可以试试“咖啡”“奶茶”或“晚餐”。': ['No sample products matched. Try “coffee”, “milk tea” or “dinner”.', 'Aucun produit fictif ne correspond. Essayez « café », « thé au lait » ou « dîner ».', '一致するサンプル商品がありません。「コーヒー」「ミルクティー」「夕食」をお試しください。', 'Keine Beispielprodukte gefunden. Versuchen Sie „Kaffee“, „Milchtee“ oder „Abendessen“.', 'Ningún producto de ejemplo coincide. Prueba «café», «té con leche» o «cena».'],
  '示例价 ¥{price} · 非实际报价': ['Sample price ¥{price} · not a real quote', 'Prix indicatif ¥{price} · non contractuel', '参考価格 ¥{price}・実際の価格ではありません', 'Beispielpreis ¥{price} · kein echter Preis', 'Precio de ejemplo ¥{price} · no es un precio real'],
  '去淘宝搜索这件商品 ↗': ['Search this on Taobao ↗', 'Rechercher sur Taobao ↗', '淘宝で検索 ↗', 'Auf Taobao suchen ↗', 'Buscar en Taobao ↗'],
  '前往平台首页 ↗': ['Open platform home ↗', 'Ouvrir la page d’accueil ↗', 'プラットフォームのトップへ ↗', 'Zur Plattform-Startseite ↗', 'Ir a la página principal ↗'],
  '本演示不创建订单、不确认支付、不占用补贴额度，不调用模型或淘宝API。': ['This demo creates no orders, confirms no payment, uses no subsidy quota and calls neither a model nor the Taobao API.', 'Cette démo ne crée aucune commande, ne confirme aucun paiement, n’utilise aucun quota de subvention et n’appelle ni modèle ni API Taobao.', 'このデモは注文作成・支払い確認・補助枠の消費を行わず、モデルや淘宝APIも呼びません。', 'Diese Demo erstellt keine Bestellungen, bestätigt keine Zahlung, verbraucht kein Zuschusskontingent und ruft weder Modell noch Taobao-API auf.', 'Esta demo no crea pedidos, no confirma pagos, no consume cupo de subvención y no llama a ningún modelo ni a la API de Taobao.'],

  // ── 知识反馈处理（脚本态提示）────────────────────────────
  '仅当前空间维护者可以读取和处理反馈；处理不会自动修改已发布制度。': ['Only maintainers of this space can read and resolve feedback; resolving never edits published policy.', 'Seuls les mainteneurs de cet espace peuvent lire et traiter les retours ; le traitement ne modifie jamais une règle publiée.', 'このスペースの管理者のみがフィードバックを閲覧・処理できます。処理しても公開済み規程は自動変更されません。', 'Nur Maintainer dieses Bereichs können Feedback lesen und bearbeiten; eine Bearbeitung ändert keine veröffentlichte Regelung.', 'Solo los mantenedores de este espacio pueden leer y resolver comentarios; resolverlos nunca modifica una política publicada.'],
  '请核对资料后填写处理说明。': ['Check the source material, then write the resolution note.', 'Vérifiez les documents puis rédigez la note de traitement.', '資料を確認してから対応内容を記入してください。', 'Bitte Unterlagen prüfen und dann die Bearbeitungsnotiz verfassen.', 'Revisa el material y luego escribe la nota de resolución.'],
  '当前没有反馈。': ['No feedback at the moment.', 'Aucun retour pour le moment.', '現在フィードバックはありません。', 'Derzeit kein Feedback.', 'No hay comentarios por ahora.'],
  '反馈不可读取，请核对维护授权、登录状态和服务。': ['Feedback could not be loaded; check maintainer permission, sign-in state and the service.', 'Impossible de charger les retours ; vérifiez l’autorisation, la session et le service.', 'フィードバックを読み込めません。管理者権限・ログイン状態・サービスを確認してください。', 'Feedback konnte nicht geladen werden. Bitte Maintainer-Rechte, Anmeldung und Dienst prüfen.', 'No se pudieron cargar los comentarios; revisa el permiso de mantenedor, la sesión y el servicio.'],
  '处理结果未确认，请重新读取核对；不会自动重复提交。': ['The result was not confirmed. Reload to verify; nothing is resubmitted automatically.', 'Résultat non confirmé. Rechargez pour vérifier ; aucun renvoi automatique.', '処理結果を確認できません。再読み込みして確認してください。自動再送はしません。', 'Ergebnis unbestätigt. Bitte neu laden; keine automatische Wiederholung.', 'El resultado no se confirmó. Recarga para verificar; no se reenvía automáticamente.'],

  // ── 账号口令（脚本态提示）────────────────────────────────
  '改密后所有已有登录将失效，请使用新口令重新登录。': ['All existing sessions end after the change; sign in again with the new password.', 'Toutes les sessions existantes prennent fin ; reconnectez-vous avec le nouveau mot de passe.', '変更後は既存のログインがすべて無効になります。新しいパスワードで再ログインしてください。', 'Alle bestehenden Sitzungen enden nach der Änderung; bitte mit dem neuen Kennwort neu anmelden.', 'Todas las sesiones existentes terminan tras el cambio; vuelve a iniciar sesión con la nueva contraseña.'],
  '两次新口令不一致。': ['The two new passwords do not match.', 'Les deux nouveaux mots de passe diffèrent.', '新しいパスワードが一致しません。', 'Die beiden neuen Kennwörter stimmen nicht überein.', 'Las dos contraseñas nuevas no coinciden.'],
  '改密未完成，请重新登录核对。': ['The password change did not complete; sign in again to verify.', 'Le changement n’a pas abouti ; reconnectez-vous pour vérifier.', 'パスワード変更が完了しませんでした。再ログインして確認してください。', 'Die Kennwortänderung wurde nicht abgeschlossen; bitte neu anmelden und prüfen.', 'El cambio de contraseña no se completó; vuelve a iniciar sesión para verificar.'],

  // ── 外壳导航与帮助说明 ─────────────────────────────────
  '知序工作空间': ['KnowingOrder workspace', 'Espace KnowingOrder', 'KnowingOrder ワークスペース', 'KnowingOrder-Arbeitsbereich', 'Espacio KnowingOrder'],
  '企业知识与工作助手': ['Enterprise knowledge and work assistant', 'Assistant connaissances et travail', '企業知識と業務アシスタント', 'Unternehmenswissen und Arbeitsassistent', 'Asistente de conocimiento y trabajo'],
  '企业工作': ['Enterprise work', 'Travail d’entreprise', '企業の業務', 'Unternehmensarbeit', 'Trabajo de empresa'],
  '公开体验': ['Public preview', 'Aperçu public', '公開プレビュー', 'Öffentliche Vorschau', 'Vista previa pública'],
  '知识有边界 · 协作有秩序': ['Knowledge has boundaries · collaboration has order', 'Le savoir a ses limites · la collaboration a ses règles', '知識には境界・協働には秩序', 'Wissen hat Grenzen · Zusammenarbeit hat Ordnung', 'El conocimiento tiene límites · la colaboración tiene orden'],
  '企业知识有边界': ['Enterprise knowledge has boundaries', 'Le savoir d’entreprise a ses limites', '企業知識には境界があります', 'Unternehmenswissen hat Grenzen', 'El conocimiento de empresa tiene límites'],
  '请勿在联网体验中输入内部资料': ['Do not enter internal material in the public preview', 'Ne saisissez pas de données internes dans l’aperçu public', '公開プレビューに社内資料を入力しないでください', 'Keine internen Daten in der öffentlichen Vorschau eingeben', 'No introduzcas material interno en la vista pública'],
  '企业账号与空间': ['Enterprise account and spaces', 'Compte et espaces d’entreprise', '企業アカウントとスペース', 'Unternehmenskonto und Bereiche', 'Cuenta y espacios de empresa'],
  '前往知识空间登录与管理': ['Sign in and manage in Knowledge spaces', 'Connexion et gestion dans les espaces de connaissance', 'ナレッジスペースでログイン・管理', 'In Wissensräumen anmelden und verwalten', 'Inicia sesión y gestiona en Espacios de conocimiento'],
  '让知识，持续生长': ['Let knowledge keep growing', 'Faire grandir le savoir', '知識を育て続ける', 'Wissen wachsen lassen', 'Que el conocimiento siga creciendo'],
  '让问题，有据可循': ['Every question, backed by evidence', 'Chaque question, appuyée par des preuves', '問いに根拠を', 'Jede Frage mit Belegen', 'Cada pregunta, con pruebas'],
  '让琐事，少点来回': ['Fewer back-and-forths on chores', 'Moins d’allers-retours pour les tâches', '雑務の往復を減らす', 'Weniger Hin und Her bei Routineaufgaben', 'Menos idas y venidas en trámites'],
  '让工作，从容开始': ['Start work at ease', 'Commencer le travail sereinement', '余裕をもって仕事を始める', 'Gelassen in die Arbeit starten', 'Empieza a trabajar con calma'],
  '对话与历史保存在企业问答的私有会话中。': ['Conversations and history stay in the private enterprise Q&A session.', 'Les échanges et l’historique restent dans la session privée des questions d’entreprise.', '会話と履歴は社内Q&Aの非公開セッションに保存されます。', 'Gespräche und Verlauf bleiben in der privaten Unternehmens-Q&A-Sitzung.', 'Las conversaciones y el historial permanecen en la sesión privada de Preguntas de empresa.'],
  '整理团队的经验，也为下一位同事留一条清晰的路。': ['Organise the team’s experience and leave a clear path for the next colleague.', 'Organisez l’expérience de l’équipe et laissez un chemin clair au prochain collègue.', 'チームの経験を整理し、次の同僚に分かりやすい道筋を残します。', 'Erfahrung des Teams ordnen und dem nächsten Kollegen einen klaren Weg hinterlassen.', 'Ordena la experiencia del equipo y deja un camino claro al siguiente colega.'],
  '企业知识，需要先核验授权。': ['Enterprise knowledge requires verified authorisation.', 'Le savoir d’entreprise exige une autorisation vérifiée.', '企業知識は権限の確認が必要です。', 'Unternehmenswissen erfordert geprüfte Berechtigung.', 'El conocimiento de empresa requiere autorización verificada.'],
  '请从企业问答进入授权空间检索。公开联网体验独立运行，不读取企业资料；请勿在此提交内部信息。': ['Search from Enterprise Q&A inside an authorised space. The public web preview runs separately, reads no enterprise material, and must not receive internal information.', 'Effectuez vos recherches depuis les questions d’entreprise dans un espace autorisé. L’aperçu web public fonctionne séparément, ne lit aucun document d’entreprise et ne doit recevoir aucune information interne.', '社内Q&Aから権限のあるスペースで検索してください。公開ウェブプレビューは独立して動作し、社内資料を読みません。内部情報を入力しないでください。', 'Bitte über Unternehmens-Q&A in einem berechtigten Bereich suchen. Die öffentliche Vorschau läuft getrennt, liest keine Unternehmensunterlagen und darf keine internen Informationen erhalten.', 'Busca desde Preguntas de empresa en un espacio autorizado. La vista web pública funciona aparte, no lee material de empresa y no debe recibir información interna.'],
  '企业模型外发默认关闭，仅对获准的虚构测试空间开放。': ['Sending enterprise data to a model is off by default and enabled only for approved fictional test spaces.', 'L’envoi de données d’entreprise à un modèle est désactivé par défaut et ouvert uniquement aux espaces de test fictifs autorisés.', '企業データのモデル送信は既定で無効で、許可された架空のテストスペースのみ有効です。', 'Das Senden von Unternehmensdaten an ein Modell ist standardmäßig aus und nur für freigegebene fiktive Testbereiche erlaubt.', 'El envío de datos de empresa a un modelo está desactivado por defecto y solo se abre para espacios de prueba ficticios aprobados.'],
  '读懂公司知识，帮你把工作做好。': ['Understand company knowledge and get the work done.', 'Comprendre le savoir de l’entreprise et faire aboutir le travail.', '会社の知識を読み解き、仕事を前に進めます。', 'Unternehmenswissen verstehen und Arbeit erledigen.', 'Entiende el conocimiento de la empresa y haz bien tu trabajo.'],
  '企业知识是主线': ['Enterprise knowledge is the main line', 'Le savoir d’entreprise est la priorité', '企業知識が主軸です', 'Unternehmenswissen ist die Hauptlinie', 'El conocimiento de empresa es la línea principal'],
  '企业账号、文字入库与授权关键词检索已接入。默认仅展示本地原文；模型只对获准的虚构测试空间开放，真实企业资料外发仍关闭。': ['Enterprise accounts, text ingestion and authorised keyword search are connected. By default only local source text is shown; models are enabled only for approved fictional test spaces, and sending real enterprise material stays off.', 'Comptes d’entreprise, ingestion de texte et recherche par mots-clés autorisée sont connectés. Par défaut, seul le texte source local est affiché ; les modèles ne sont ouverts qu’aux espaces de test fictifs autorisés, l’envoi de documents réels reste désactivé.', '企業アカウント、テキスト取り込み、権限付きキーワード検索に対応しています。既定ではローカル原文のみを表示し、モデルは許可された架空テストスペースのみ有効です。実際の社内資料の送信は無効のままです。', 'Unternehmenskonten, Textaufnahme und berechtigte Stichwortsuche sind angebunden. Standardmäßig wird nur lokaler Originaltext gezeigt; Modelle sind nur für freigegebene fiktive Testbereiche aktiv, das Senden echter Unternehmensunterlagen bleibt aus.', 'Las cuentas de empresa, la ingesta de texto y la búsqueda autorizada por palabras clave están conectadas. Por defecto solo se muestra el texto original local; los modelos solo se abren para espacios de prueba ficticios aprobados y el envío de material real sigue desactivado.'],
  '公开体验单独使用': ['Public preview is separate', 'L’aperçu public est séparé', '公開プレビューは独立しています', 'Öffentliche Vorschau ist getrennt', 'La vista pública es independiente'],
  '联网问答仍可用于公开问题，请勿输入企业内部资料。页面会话不持久保存，刷新后清空；重要结果请导出。': ['Web Q&A remains available for public questions; do not enter internal material. Sessions are not persisted and clear on refresh; export anything important.', 'Les questions web restent disponibles pour des sujets publics ; ne saisissez pas de données internes. Les sessions ne sont pas conservées et s’effacent au rechargement ; exportez ce qui compte.', '公開の質問にはウェブ問答を利用できます。社内資料は入力しないでください。セッションは保存されず、再読み込みで消えます。重要な結果は書き出してください。', 'Web-Q&A bleibt für öffentliche Fragen verfügbar; keine internen Daten eingeben. Sitzungen werden nicht gespeichert und beim Neuladen geleert; Wichtiges bitte exportieren.', 'Las preguntas web siguen disponibles para temas públicos; no introduzcas material interno. Las sesiones no se guardan y se borran al recargar; exporta lo importante.'],
  '主导航': ['Main navigation', 'Navigation principale', 'メインナビゲーション', 'Hauptnavigation', 'Navegación principal'],
  '问答工作区': ['Q&A workspace', 'Espace questions-réponses', 'Q&Aワークスペース', 'Q&A-Arbeitsbereich', 'Espacio de preguntas'],
  '公开联网问答体验 · 不检索企业知识，请勿输入内部资料': ['Public web Q&A preview · no enterprise knowledge, do not enter internal material', 'Aperçu web public · sans savoir d’entreprise, ne saisissez pas de données internes', '公開ウェブ問答・社内知識は検索しません。社内資料を入力しないでください', 'Öffentliche Web-Q&A · kein Unternehmenswissen, keine internen Daten eingeben', 'Vista web pública · sin conocimiento de empresa, no introduzcas material interno'],
  '需要企业账号登录，回答只使用你获授权且已发布的资料': ['Requires an enterprise account; answers use only published material you are authorised to see', 'Connexion d’entreprise requise ; les réponses n’utilisent que les documents publiés que vous êtes autorisé à voir', '企業アカウントが必要です。回答は権限のある公開済み資料のみを使用します', 'Unternehmenskonto erforderlich; Antworten nutzen nur veröffentlichte Unterlagen, für die Sie berechtigt sind', 'Requiere cuenta de empresa; las respuestas solo usan material publicado al que tienes acceso'],
  '请勿输入内部资料 · 刷新后会话清空': ['Do not enter internal material · session clears on refresh', 'Ne saisissez pas de données internes · session effacée au rechargement', '社内資料を入力しないでください・再読み込みでセッションは消えます', 'Keine internen Daten eingeben · Sitzung wird beim Neuladen geleert', 'No introduzcas material interno · la sesión se borra al recargar'],
  '参考资料与回答详情': ['Sources and answer details', 'Sources et détails de la réponse', '出典と回答の詳細', 'Quellen und Antwortdetails', 'Fuentes y detalles de la respuesta'],
  '员工事务是辅助': ['Employee affairs is secondary', 'Les affaires RH sont secondaires', '従業員手続きは補助的です', 'Mitarbeiteraufgaben sind ergänzend', 'Los trámites del empleado son secundarios'],
  '可体验模拟商品查询，跳转会打开淘宝搜索页并填入商品名。补贴、参与确认与财务结算已接入，但没有真实订单或支付。': ['You can try sample product search; the link opens a Taobao search page with the product name filled in. Subsidies, confirmations and finance settlement are connected, but there are no real orders or payments.', 'Vous pouvez essayer la recherche de produits fictifs ; le lien ouvre une recherche Taobao préremplie. Subventions, confirmations et règlements financiers sont connectés, sans commande ni paiement réels.', 'サンプル商品検索を試せます。リンクは商品名を入れた淘宝検索ページを開きます。補助金・確認・財務精算は接続済みですが、実際の注文や支払いはありません。', 'Beispiel-Produktsuche ist testbar; der Link öffnet eine Taobao-Suche mit Produktnamen. Zuschüsse, Bestätigungen und Finanzabrechnung sind angebunden, aber ohne echte Bestellungen oder Zahlungen.', 'Puedes probar la búsqueda de productos de ejemplo; el enlace abre una búsqueda de Taobao con el nombre escrito. Subvenciones, confirmaciones y liquidación financiera están conectadas, sin pedidos ni pagos reales.'],
  '有据可循': ['Backed by evidence', 'Appuyé par des preuves', '根拠あり', 'Mit Belegen', 'Con pruebas'],
  '公开联网体验': ['Public web preview', 'Aperçu web public', '公開ウェブ体験', 'Öffentliche Websuche', 'Vista web pública'],

  // ── 工作台概览 ──────────────────────────────────────
  '我的工作概览': ['My work overview', 'Ma vue d’ensemble', '私の作業概要', 'Meine Arbeitsübersicht', 'Mi resumen de trabajo'],
  '今天要处理的事': ['What needs attention today', 'À traiter aujourd’hui', '今日対応すること', 'Heute zu erledigen', 'Lo que hay que atender hoy'],
  '按你的权限统计': ['counted within your permissions', 'selon vos autorisations', '権限の範囲で集計', 'nach Ihren Berechtigungen gezählt', 'según tus permisos'],
  '重新读取': ['Reload', 'Recharger', '再読み込み', 'Neu laden', 'Recargar'],
  '正在读取你的空间与待办…': ['Loading your spaces and to-dos…', 'Chargement de vos espaces et tâches…', 'スペースと ToDo を読み込み中…', 'Bereiche und Aufgaben werden geladen…', 'Cargando tus espacios y pendientes…'],
  '用企业账号登录': ['Sign in with an enterprise account', 'Se connecter avec un compte d’entreprise', '企業アカウントでログイン', 'Mit Unternehmenskonto anmelden', 'Inicia sesión con una cuenta de empresa'],
  '这一项没读出来，可稍后刷新': ['This figure could not be loaded; refresh later', 'Cette valeur n’a pas pu être chargée ; actualisez plus tard', 'この項目を取得できませんでした。後で再読み込みしてください', 'Dieser Wert konnte nicht geladen werden; später aktualisieren', 'No se pudo cargar este dato; actualiza más tarde'],
  '可访问知识空间': ['Accessible knowledge spaces', 'Espaces de connaissance accessibles', 'アクセス可能なナレッジスペース', 'Zugängliche Wissensbereiche', 'Espacios de conocimiento accesibles'],
  '待我确认': ['Awaiting my confirmation', 'En attente de ma confirmation', '私の確認待ち', 'Warten auf meine Bestätigung', 'Pendiente de mi confirmación'],
  '我的申请': ['My applications', 'Mes demandes', '自分の申請', 'Meine Anträge', 'Mis solicitudes'],
  '个': ['', '', '', '', ''],
  '条': ['', '', '', '', ''],
  '已按你的授权范围筛选': ['Filtered by your permissions', 'Filtré selon vos autorisations', '権限範囲で絞り込み済み', 'Nach Ihren Berechtigungen gefiltert', 'Filtrado según tus permisos'],
  '确认或拒绝后，整单才会继续': ['The request proceeds only after everyone confirms or declines', 'La demande avance après confirmation ou refus de chacun', '全員の確認または辞退後に申請が進みます', 'Der Antrag läuft erst nach Bestätigung oder Ablehnung aller weiter', 'La solicitud continúa tras confirmar o rechazar todos'],
  '暂时没有要你确认的': ['Nothing needs your confirmation right now', 'Rien à confirmer pour le moment', '現在確認待ちはありません', 'Derzeit nichts zu bestätigen', 'Nada que confirmar por ahora'],
  '最近一笔：{status}': ['Latest: {status}', 'Dernière : {status}', '最新：{status}', 'Letzte: {status}', 'Última: {status}'],
  '还没有提交过补贴申请': ['No subsidy application submitted yet', 'Aucune demande de subvention envoyée', '補助申請はまだありません', 'Noch kein Zuschussantrag gestellt', 'Aún no has enviado solicitudes de subvención'],
  '已提交': ['Submitted', 'Soumis', '提出済み', 'Eingereicht', 'Enviada'],
  '待参与人确认': ['Awaiting participant confirmation', 'En attente des participants', '参加者の確認待ち', 'Warten auf Teilnehmer', 'Pendiente de participantes'],
  '待主管批准': ['Awaiting manager approval', 'En attente du responsable', '上長の承認待ち', 'Warten auf Freigabe', 'Pendiente de aprobación'],
  '待结算': ['Awaiting settlement', 'En attente de règlement', '精算待ち', 'Warten auf Abrechnung', 'Pendiente de liquidación'],
  '已结算': ['Settled', 'Réglé', '精算済み', 'Abgerechnet', 'Liquidada'],
  '已退回': ['Returned', 'Renvoyé', '差し戻し', 'Zurückgewiesen', 'Devuelta'],
  '已撤回': ['Withdrawn', 'Retiré', '取り下げ済み', 'Zurückgezogen', 'Retirada'],
  '登录企业账号后，这里会显示你能访问的空间与待办。': ['After signing in with an enterprise account, the spaces you can access and your to-dos appear here.', 'Après connexion avec un compte d’entreprise, vos espaces accessibles et vos tâches apparaissent ici.', '企業アカウントでログインすると、アクセス可能なスペースと ToDo がここに表示されます。', 'Nach der Anmeldung mit einem Unternehmenskonto erscheinen hier Ihre Bereiche und Aufgaben.', 'Tras iniciar sesión con una cuenta de empresa, aquí aparecen tus espacios y pendientes.'],
  '登录信息里还没有企业归属，请用企业账号重新登录。': ['Your sign-in has no enterprise attached; sign in again with an enterprise account.', 'Votre session n’est liée à aucune entreprise ; reconnectez-vous avec un compte d’entreprise.', 'ログイン情報に企業が紐づいていません。企業アカウントで再ログインしてください。', 'Ihre Anmeldung ist keinem Unternehmen zugeordnet; bitte mit einem Unternehmenskonto neu anmelden.', 'Tu sesión no tiene empresa asociada; vuelve a iniciar sesión con una cuenta de empresa.'],
  '账号已登录，但还没有分配企业。请联系管理员开通成员身份。': ['Signed in, but no enterprise is assigned yet. Ask an administrator to add you as a member.', 'Connecté, mais aucune entreprise attribuée. Demandez à un administrateur de vous ajouter comme membre.', 'ログイン済みですが企業が割り当てられていません。管理者にメンバー登録を依頼してください。', 'Angemeldet, aber noch keinem Unternehmen zugeordnet. Bitten Sie eine Administration um die Mitgliedschaft.', 'Sesión iniciada pero sin empresa asignada. Pide a un administrador que te añada como miembro.'],
  '概览暂时没读出来，可能是网络或服务端还没就绪。': ['The overview could not be loaded; the network or the service may not be ready.', 'La vue d’ensemble n’a pas pu être chargée ; le réseau ou le service n’est peut-être pas prêt.', '概要を取得できませんでした。ネットワークまたはサービスが未準備の可能性があります。', 'Die Übersicht konnte nicht geladen werden; Netzwerk oder Dienst sind evtl. noch nicht bereit.', 'No se pudo cargar el resumen; la red o el servicio quizá no estén listos.'],
  '概览暂时没读出来。': ['The overview could not be loaded.', 'La vue d’ensemble n’a pas pu être chargée.', '概要を取得できませんでした。', 'Die Übersicht konnte nicht geladen werden.', 'No se pudo cargar el resumen.'],
};
