package cn.adinall.ai.types.common;

/**
 * 通用常量（types 模块最底层的共享字面量）
 * <p>
 * 功能：收拢「被多层复用、且不随环境变化」的字面量，避免同一个分隔符在各层各写一遍。
 * <pre>
 *   SPLIT   逗号 ","，用于拼接/拆分以逗号分隔的字符串
 * </pre>
 * 运行过程（常量类的典型用法）：
 * <pre>
 *   某个需要处理逗号分隔串的类
 *        |
 *        | String.join(Constants.SPLIT, List.of("a","b","c"))
 *        | 或 "a,b,c".split(Constants.SPLIT)
 *        v
 *   "a,b,c"   或   ["a","b","c"]
 * </pre>
 * 案例：
 * <pre>
 *   取值: Constants.SPLIT = ","
 *   用法: String.join(Constants.SPLIT, List.of("flashhead","flashtalk"))
 *   输出: "flashhead,flashtalk"
 *   现状: 本仓库内 grep SPLIT 只命中定义行 —— 目前没有调用点，属于历史遗留的通用常量
 * </pre>
 * 设计要点：
 * <ul>
 *   <li>只放编译期常量、不放配置：需要按环境调整的东西（超时、开关、CORS 来源）一律走
 *       application.yml，常量一旦编译进各处就无法按部署改。</li>
 *   <li>本类没有私有构造、也没有 final 修饰（历史写法）：新加常量前先确认它真的要被两处以上复用，
 *       否则只是把本该局部的东西提升成了全局耦合点。</li>
 *   <li>常量名是通用词（SPLIT）而不是具体用途名：多处复用同一个分隔符时不会出现
 *       「这里叫 COMMA、那里叫 SEPARATOR」的两套写法。</li>
 * </ul>
 */
public class Constants {

    public final static String SPLIT = ",";

}
