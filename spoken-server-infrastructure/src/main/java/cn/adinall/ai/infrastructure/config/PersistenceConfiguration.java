package cn.adinall.ai.infrastructure.config;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * 持久化装配：把 spoken-ai.* 配置绑成 Bean，并把 MyBatis 的 DAO 接口扫成 Mapper。
 *
 * 为什么显式写 @MapperScan：Spring Boot 的 MyBatis 自动配置默认只扫「启动类所在包」，
 * 而启动类在 cn.adinall.ai.app 下，扫不到 cn.adinall.ai.infrastructure.dao。显式声明最省心，
 * 也不会因为启动类搬家而突然「DAO 找不到了」。
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(AppProperties.class)
@MapperScan("cn.adinall.ai.infrastructure.dao")
public class PersistenceConfiguration {
}