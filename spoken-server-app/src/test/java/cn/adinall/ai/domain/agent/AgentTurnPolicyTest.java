package cn.adinall.ai.domain.agent;

import cn.adinall.ai.domain.conversation.model.valobj.TeachingOutcomeEnum;

import cn.adinall.ai.domain.agent.model.valobj.AiAgentRegisterVO;
import cn.adinall.ai.domain.agent.service.armory.factory.DefaultArmoryFactory;
import cn.adinall.ai.domain.agent.service.armory.matter.patch.MySpringAI;
import cn.adinall.ai.domain.agent.service.chat.ChatService;
import cn.adinall.ai.domain.conversation.model.valobj.TeachingTaskVO;
import cn.adinall.ai.domain.conversation.service.reply.ReplyPromptBuilder;
import cn.adinall.ai.domain.conversation.service.reply.TeachingPolicy;
import cn.adinall.ai.domain.learner.model.valobj.EnglishLevelEnum;
import com.google.adk.agents.LlmAgent;
import com.google.adk.runner.InMemoryRunner;
import org.junit.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.model.StreamingChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.test.util.ReflectionTestUtils;
import org.yaml.snakeyaml.Yaml;
import reactor.core.publisher.Flux;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;
import static org.mockito.Mockito.*;

/**
 * 功能：验证本轮规则实际进入 SYSTEM、并发轮次隔离，以及配置模型的真实流式回复。
 * <p>体系架构 / 运行过程：</p>
 * <pre>case policy -> ChatService state -> real ADK -> MySpringAI -> captured/live model prompt</pre>
 * <p>案例：L2“你说的是什么？” -> SYSTEM 为 L2/HELP/QUESTION，原话留在 USER。</p>
 * <p>设计要点：</p>
 * <ul><li>使用真实 ADK 装配与消息转换，避免只检查拼接字符串。</li>
 * <li>真实模型验证需显式启用 spoken.liveModelTest，默认测试不请求网络。</li>
 * <li>不启动 Java 服务，密钥只从环境变量读取，验证文本均为合成样例。</li></ul>
 */
public class AgentTurnPolicyTest {
    /**
     * 合成原话验证两层准则的可观察结果：相关追问，以及高总分下的温和具体纠音。
     * 显式开关才访问当前配置模型；不使用真实账号，不执行任何业务工具或保存偏好。
     */
    @Test public void configuredModelKeepsConversationMovingAndOffersGentlePronunciationGuidance() {
        assumeTrue(Boolean.getBoolean("spoken.verify.coaching-principles"));
        String key = System.getenv("LK888_API_KEY");
        assertNotNull("需要当前模型环境密钥", key);
        var config = module();
        var apiConfig = (Map<?, ?>) config.get("ai-api");
        var modelConfig = (Map<?, ?>) config.get("chat-model");
        var api = OpenAiApi.builder().baseUrl((String) apiConfig.get("base-url")).apiKey(key)
                .completionsPath("/" + apiConfig.get("completions-path")).build();
        var model = OpenAiChatModel.builder().openAiApi(api).defaultOptions(OpenAiChatOptions.builder()
                .model((String) modelConfig.get("model")).temperature(((Number) modelConfig.get("temperature")).doubleValue())
                .reasoningEffort((String) modelConfig.get("reasoning-effort"))
                .toolChoice("none").internalToolExecutionEnabled(false).build()).build();
        var service = service(runner(model));
        var previous = new TeachingTaskVO("READ", "I cooked dinner.", "", 0);
        var feedback = new cn.adinall.ai.domain.conversation.model.valobj.PronunciationFeedbackVO(92., List.of(
                new cn.adinall.ai.domain.conversation.model.valobj.PronunciationFeedbackVO.IssueVO("cooked", "kʊkt", "kʊk", .95)));
        var decisions = List.of(
                TeachingPolicy.decide(EnglishLevelEnum.L3, TeachingTaskVO.question(""), "I like reading books", null, true, .9),
                TeachingPolicy.decide(EnglishLevelEnum.L2, previous, "I cooked dinner", feedback, true));
        for (int i = 0; i < decisions.size(); i++) {
            var decision = decisions.get(i);
            String input = i == 0 ? "I like reading books" : "I cooked dinner";
            var chunks = service.handlePreparedTextStream("coach", "coaching-principles-test",
                    ReplyPromptBuilder.buildManaged(input, decision, null, i == 0 ? TeachingTaskVO.question("") : previous),
                    ReplyPromptBuilder.buildSystemPolicy(decision)).collectList().block(Duration.ofSeconds(45));
            String reply = String.join("", chunks);
            String played = String.join("", cn.adinall.ai.domain.conversation.service.reply.TeachingReplyGuard
                    .apply(Flux.fromIterable(chunks), decision).collectList().block());
            System.out.println("LIVE_COACHING_UTF8 " + i + " " + Base64.getEncoder().encodeToString(played.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
            for (String judgment : List.of("你错了", "读错了", "不对", "失败", "you are wrong", "incorrect", "❌", "✗"))
                assertFalse(reply, reply.toLowerCase(Locale.ROOT).contains(judgment));
            if (i == 0) {
                String question = cn.adinall.ai.domain.conversation.service.reply.SpokenTaskResolver.lastQuestion(played);
                assertFalse(played, question.isBlank());
                assertTrue(played, question.toLowerCase(Locale.ROOT).matches("(?s).*(book|read).*"));
                assertFalse(played, played.toLowerCase(Locale.ROOT).contains("i like reading books too"));
            } else {
                assertTrue(played, played.contains("cooked"));
                assertTrue(played, played.matches("(?s).*(结尾|收住|短促|轻轻|尾音).*"));
                assertTrue(played, cn.adinall.ai.domain.conversation.service.reply.SpokenTaskResolver.resolve(decision.nextTask(), played).reading());
                var english = java.util.regex.Pattern.compile("[A-Za-z]").matcher(played);
                var chinese = java.util.regex.Pattern.compile("\\p{IsHan}").matcher(played);
                assertTrue(played, english.find());
                assertTrue(played, chinese.find());
                assertTrue(played, english.start() < chinese.start());
            }
        }
    }

    /** 当前配置模型与 ADK 链路验证：英语知识问题先解答，不变成跟读，播报护栏保留说明。 */
    @Test public void configuredModelAnswersEnglishKnowledgeWithoutForcedReading() {
        assumeTrue(Boolean.getBoolean("spoken.verify.english-question"));
        String key=System.getenv("LK888_API_KEY");
        assertNotNull("需要当前模型环境密钥",key);
        var config=module(); var apiConfig=(Map<?,?>)config.get("ai-api"); var modelConfig=(Map<?,?>)config.get("chat-model");
        var api=OpenAiApi.builder().baseUrl((String)apiConfig.get("base-url")).apiKey(key)
                .completionsPath("/"+apiConfig.get("completions-path")).build();
        var model=OpenAiChatModel.builder().openAiApi(api).defaultOptions(OpenAiChatOptions.builder()
                .model((String)modelConfig.get("model")).temperature(((Number)modelConfig.get("temperature")).doubleValue())
                .reasoningEffort((String)modelConfig.get("reasoning-effort"))
                .toolChoice("none").internalToolExecutionEnabled(false).build()).build();
        var service=service(runner(model));
        var previous=new TeachingTaskVO("READ","I cooked dinner.","",0);
        var feedback=new cn.adinall.ai.domain.conversation.model.valobj.PronunciationFeedbackVO(30.,List.of(
                new cn.adinall.ai.domain.conversation.model.valobj.PronunciationFeedbackVO.IssueVO("cooked","kʊkt","kʊk",.95)));
        int sample=0;
        for(String input:List.of("apple 是什么意思？","I cooked dinner 这句话为什么用过去式？")) {
            var decision=TeachingPolicy.decide(EnglishLevelEnum.L2,previous,input,feedback,true,0);
            assertEquals(TeachingOutcomeEnum.ENGLISH_QUESTION,decision.outcome());
            var chunks=service.handlePreparedTextStream("coach","english-question-test",
                    ReplyPromptBuilder.buildManaged(input,decision,null,previous),ReplyPromptBuilder.buildSystemPolicy(decision))
                    .collectList().block(Duration.ofSeconds(45));
            String played=String.join("",cn.adinall.ai.domain.conversation.service.reply.TeachingReplyGuard
                    .apply(Flux.fromIterable(chunks),decision).collectList().block());
            System.out.println("LIVE_ENGLISH_QUESTION_UTF8 "+sample+" "+Base64.getEncoder()
                    .encodeToString(played.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
            assertFalse(played,played.isBlank());
            for(String forced:List.of("请跟读","跟我读","读一遍","repeat after me","读错了","你错了","发音有问题"))
                assertFalse(played,played.toLowerCase(Locale.ROOT).contains(forced));
            assertTrue(played,sample==0 ? played.contains("苹果") : played.contains("过去"));
            assertTrue(played,played.contains(sample==0 ? "apple" : "cooked"));
            assertFalse(cn.adinall.ai.domain.conversation.service.reply.SpokenTaskResolver.resolve(decision.nextTask(),played).reading());
            sample++;
        }
    }

    /** 读取生产 Agent 配置，使测试与实际系统指令使用同一来源。 */
    @SuppressWarnings("unchecked")
    private Map<String, Object> module() {
        Map<String, Object> data = new Yaml().load(getClass().getResourceAsStream("/agent/spoken-server.yml"));
        for (String key : List.of("ai", "agent", "config", "tables", "spokenServer", "module"))
            data = (Map<String, Object>) data.get(key);
        return data;
    }

    @SuppressWarnings("unchecked")
    private InMemoryRunner runner(StreamingChatModel model) {
        var agent = ((List<Map<String, Object>>) module().get("agents")).get(0);
        return new InMemoryRunner(LlmAgent.builder().name((String) agent.get("name"))
                .description((String) agent.get("description"))
                .instruction((String) agent.get("instruction"))
                .model(new MySpringAI(model, "bonsai-2-27b")).build(), "policy-test");
    }

    private ChatService service(InMemoryRunner runner) {
        var factory = mock(DefaultArmoryFactory.class);
        when(factory.getAiAgentRegisterVO("coach")).thenReturn(AiAgentRegisterVO.builder()
                .appName("policy-test").runner(runner).build());
        var service = new ChatService();
        ReflectionTestUtils.setField(service, "defaultArmoryFactory", factory);
        return service;
    }

    @Test public void realAdkSeparatesConcurrentSystemPoliciesFromUserData() {
        List<Prompt> captured = new CopyOnWriteArrayList<>();
        var runner = runner(prompt -> {
            captured.add(prompt);
            return Flux.just(new ChatResponse(List.of(new Generation(new AssistantMessage("Hi?")))));
        });
        var service = service(runner);
        var low = TeachingPolicy.decide(EnglishLevelEnum.L2, TeachingTaskVO.question(""), "low-marker", null, false, .9);
        var high = TeachingPolicy.decide(EnglishLevelEnum.L5, TeachingTaskVO.question(""), "high-marker", null, false, .9);
        Flux.merge(service.handlePreparedTextStream("coach", "same-user", "low-marker", ReplyPromptBuilder.buildSystemPolicy(low)),
                service.handlePreparedTextStream("coach", "same-user", "high-marker", ReplyPromptBuilder.buildSystemPolicy(high)))
                .collectList().block(Duration.ofSeconds(10));
        assertEquals(2, captured.size());
        for (var prompt : captured) {
            String system = prompt.getSystemMessage().getText();
            String user = prompt.getUserMessage().getText();
            assertFalse(system.contains("{turn_policy"));
            assertFalse(system.contains("marker"));
            assertEquals(2, prompt.getInstructions().size());
            assertTrue(system.contains("学员产品档位：" + (user.equals("low-marker") ? "L2" : "L5")));
            assertFalse(system.contains("学员产品档位：" + (user.equals("low-marker") ? "L5" : "L2")));
        }
        assertTrue(runner.sessionService().listSessions("policy-test", "same-user").blockingGet().sessions().isEmpty());
        // 通用三参数调用没有教学策略时，可选占位符消失，不报缺少 state。
        service.handlePreparedTextStream("coach", "generic", "Hi").collectList().block(Duration.ofSeconds(10));
        assertFalse(captured.get(2).getSystemMessage().getText().contains("{turn_policy"));
    }

    @Test public void realStreamKeepsPunctuationAndCompleteSentenceChunks() {
        var runner = runner(prompt -> Flux.fromIterable(List.of("先说中文。", "I like coffee.", "\n", "Do you like tea", "?"))
                .map(text -> new ChatResponse(List.of(new Generation(new AssistantMessage(text))))));
        var chunks = service(runner).handlePreparedTextStream("coach", "punctuation-test", "Hi")
                .collectList().block(Duration.ofSeconds(10));
        assertEquals("先说中文。I like coffee.\nDo you like tea?", String.join("", chunks));
        assertEquals("Do you like tea?", cn.adinall.ai.domain.conversation.service.reply.SpokenTaskResolver.lastQuestion(String.join("", chunks)));
    }

    /** 当前配置模型端到端验证：问句跟读不作为提问，L1/L2 英文先播、中文译意实际保留。 */
    @Test public void configuredModelKeepsReadingQuestionsAsExercisesAndExplainsAfterEnglish() {
        assumeTrue(Boolean.getBoolean("spoken.verify.reading-question"));
        String key=System.getenv("LK888_API_KEY");
        assertNotNull("需要当前模型环境密钥",key);
        var config=module(); var apiConfig=(Map<?,?>)config.get("ai-api"); var modelConfig=(Map<?,?>)config.get("chat-model");
        var api=OpenAiApi.builder().baseUrl((String)apiConfig.get("base-url")).apiKey(key)
                .completionsPath("/"+apiConfig.get("completions-path")).build();
        var model=OpenAiChatModel.builder().openAiApi(api).defaultOptions(OpenAiChatOptions.builder()
                .model((String)modelConfig.get("model")).temperature(((Number)modelConfig.get("temperature")).doubleValue())
                .toolChoice("none").internalToolExecutionEnabled(false).build()).build();
        var service=service(runner(model));
        for(var level:List.of(EnglishLevelEnum.L1,EnglishLevelEnum.L2)) {
            var previous=new TeachingTaskVO("READ","How are you?","",0);
            var decision=TeachingPolicy.decide(level,previous,"How are you?",
                    new cn.adinall.ai.domain.conversation.model.valobj.PronunciationFeedbackVO(92.,List.of()),true);
            assertEquals(TeachingOutcomeEnum.READ_PASS,decision.outcome()); assertTrue(decision.practiceInput());
            var chunks=service.handlePreparedTextStream("coach","reading-question-test",
                    ReplyPromptBuilder.buildManaged("How are you?",decision,null,previous),ReplyPromptBuilder.buildSystemPolicy(decision))
                    .collectList().block(Duration.ofSeconds(45));
            String reply=String.join("",chunks);
            String played=String.join("",cn.adinall.ai.domain.conversation.service.reply.TeachingReplyGuard
                    .apply(Flux.fromIterable(chunks),decision).collectList().block());
            System.out.println("LIVE_READING_QUESTION "+level+" -> "+played);
            System.out.println("LIVE_READING_UTF8 "+level+" "+java.util.Base64.getEncoder().encodeToString(reply.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
            assertFalse(reply,reply.toLowerCase(Locale.ROOT).matches("(?s).*(i'm|i am|i’m) (fine|good|well).*"));
            var english=java.util.regex.Pattern.compile("[A-Za-z]").matcher(played);
            var chinese=java.util.regex.Pattern.compile("\\p{IsHan}").matcher(played);
            assertTrue(played,english.find()); assertTrue(played,chinese.find());
            assertTrue(played,english.start()<chinese.start());
            assertFalse(played,cn.adinall.ai.domain.conversation.service.reply.SpokenTaskResolver.lastQuestion(played).isBlank());
        }
    }

    @Test public void configuredModelHonorsHelpReadingAndStableLevels() throws Exception {
        assumeTrue("显式启用才请求真实模型", Boolean.getBoolean("spoken.liveModelTest"));
        String key = System.getenv("LK888_API_KEY");
        assertNotNull("真实模型测试需要 LK888_API_KEY", key);
        var config = module();
        var apiConfig = (Map<?, ?>) config.get("ai-api");
        var modelConfig = (Map<?, ?>) config.get("chat-model");
        var api = OpenAiApi.builder().baseUrl((String) apiConfig.get("base-url"))
                .apiKey(key).completionsPath("/" + apiConfig.get("completions-path")).build();
        var model = OpenAiChatModel.builder().openAiApi(api).defaultOptions(OpenAiChatOptions.builder()
                .model((String) modelConfig.get("model"))
                .temperature(((Number) modelConfig.get("temperature")).doubleValue())
                .reasoningEffort((String) modelConfig.get("reasoning-effort")).build()).build();
        var runner = runner(model);
        var service = service(runner);
        List<Map<String, Object>> results = new ArrayList<>();
        List<String> violations = new ArrayList<>();
        java.util.function.BiConsumer<Boolean, String> check = (passed, message) -> { if (!passed) violations.add(message); };
        // 英文历史不能把低等级带成全英文；理解求助优先于纯中文跟读。
        for (var level : EnglishLevelEnum.values()) {
            for (int attempt = 0; attempt < (level == EnglishLevelEnum.L2 ? 3 : 1); attempt++) {
                String input = level == EnglishLevelEnum.L1 || level == EnglishLevelEnum.L2 || level == EnglishLevelEnum.L5
                        ? "你说的是什么？" : "I like coffee.";
                var previous = TeachingTaskVO.question("What did you do this weekend?");
                var decision = TeachingPolicy.decide(level, previous, input, null, false, .9);
                var context = new cn.adinall.ai.domain.agent.service.context.ChatContextService(List.of(request -> List.of(
                        new cn.adinall.ai.domain.agent.model.valobj.ContextSectionVO("previous spoken reply", 10, "What did you do this weekend?"))), 6000);
                String user = context.build(new cn.adinall.ai.domain.agent.model.valobj.ChatContextRequestVO("live-test", "live-test", input,
                        ReplyPromptBuilder.buildManaged(input, decision, null, previous), ReplyPromptBuilder.buildSystemPolicy(decision)));
                var chunks = service.handlePreparedTextStream("coach", "live-test", user,
                        ReplyPromptBuilder.buildSystemPolicy(decision)).collectList().block(Duration.ofSeconds(60));
                String reply = String.join("", chunks);
                String played = String.join("", cn.adinall.ai.domain.conversation.service.reply.TeachingReplyGuard.apply(Flux.fromIterable(chunks), decision).collectList().block());
                results.add(Map.of("level", level.name(), "input", input, "action", decision.nextTask().action(), "reply", reply, "playedReply", played, "chunks", chunks.size()));
                System.out.println("LIVE_POLICY " + level + " " + decision.outcome() + " -> " + reply);
                check.accept(!reply.isBlank(), "模型无正文");
                if (level.ordinal() <= 1 || decision.outcome() != TeachingOutcomeEnum.HELP)
                    check.accept(reply.matches("(?s).*[A-Za-z].*"), level + " 应包含英语练习内容: " + reply);
                if (decision.chineseSupport()) check.accept(reply.matches("(?s).*[\\p{IsHan}].*"), level + " 本轮需要中文支持: " + reply);
                if (level.ordinal() <= 1) {
                    check.accept(played.matches("(?s).*[A-Za-z].*") && played.matches("(?s).*[\\p{IsHan}].*"),
                            level + " 实际播报也必须保留中文支援和英文任务: " + played);
                    check.accept(!cn.adinall.ai.domain.conversation.service.reply.SpokenTaskResolver.lastQuestion(played).isBlank(),
                            level + " 实际问题必须能保存，不能丢问号: " + played);
                }
                if (decision.outcome() == TeachingOutcomeEnum.HELP) {
                    assertEquals("QUESTION", decision.nextTask().action());
                    check.accept(!reply.toLowerCase(Locale.ROOT).matches("(?s).*(跟读|跟我读|repeat after|read that|read this).*"), "求助不能变为跟读: " + reply);
                    check.accept(!(reply.contains("I am Amy") || reply.contains("I am Alex")), "不能编造用户姓名");
                }
            }
        }
        var previous = TeachingTaskVO.question("What do you like to drink?");
        var reading = TeachingPolicy.decide(EnglishLevelEnum.L2, previous, "我喜欢咖啡", null, false, .9);
        var chunks = service.handlePreparedTextStream("coach", "live-test",
                ReplyPromptBuilder.buildManaged("我喜欢咖啡", reading, null, previous),
                ReplyPromptBuilder.buildSystemPolicy(reading)).collectList().block(Duration.ofSeconds(60));
        String reply = String.join("", chunks);
        System.out.println("LIVE_POLICY L2 READ -> " + reply);
        results.add(Map.of("level", "L2", "input", "我喜欢咖啡", "action", "READ", "reply", reply, "chunks", chunks.size()));
        check.accept(reply.matches("(?s).*[\\p{IsHan}].*"), "跟读需要中文支援: " + reply);
        check.accept(reply.toLowerCase(Locale.ROOT).contains("coffee"), "示范句需要来自当前内容: " + reply);
        check.accept(cn.adinall.ai.domain.conversation.service.reply.SpokenTaskResolver.resolve(reading.nextTask(), reply).reading(), "跟读目标与邀请应实际播出: " + reply);
        var actual = cn.adinall.ai.domain.conversation.service.reply.SpokenTaskResolver.resolve(reading.nextTask(), reply);
        check.accept(actual.target() != null && actual.target().trim().split("\\s+").length >= 3,
                "L2 应给完整短句而非单词: " + reply);
        check.accept(!reply.matches("(?s).*(你说得很棒|你的英语真好|你说得很好).*"), "不能评判英语能力: " + reply);
        Files.writeString(Path.of("target", "teaching-live-replies.json"),
                new com.fasterxml.jackson.databind.ObjectMapper().writerWithDefaultPrettyPrinter().writeValueAsString(results));
        assertTrue(runner.sessionService().listSessions("policy-test", "live-test").blockingGet().sessions().isEmpty());
        assertTrue(String.join("\n", violations), violations.isEmpty());
    }
}
