package cn.adinall.ai.architecture;

import cn.adinall.ai.cases.teaching.GuidedReadingCase;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Parameter;
import java.util.HashSet;
import org.springframework.context.annotation.ClassPathBeanDefinitionScanner;

import cn.adinall.ai.cases.security.ICurrentActor;
import cn.adinall.ai.domain.identity.model.valobj.AccountPrincipalVO;
import org.junit.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import java.time.Clock;
import java.util.*;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

/** Assembles all real application beans with explicit domain substitutes; circular dependencies fail refresh. */
public class CaseAssemblyTest {
    @Test public void allUseCasesAssembleWithRequiredDependenciesAndNoLazyCycle() throws Exception {
        try(AnnotationConfigApplicationContext context=new AnnotationConfigApplicationContext()) {
            ClassPathBeanDefinitionScanner scanner=new org.springframework.context.annotation.ClassPathBeanDefinitionScanner(context);
            scanner.addExcludeFilter((metadata, factory) -> metadata.getResource().getURL().toString().contains("/test-classes/"));
            scanner.scan("cn.adinall.ai.cases");
            HashSet<Class<?>> external=new HashSet<Class<?>>();
            for(String name:context.getBeanDefinitionNames()) {
                String className=context.getBeanDefinition(name).getBeanClassName();
                if(className==null || !className.startsWith("cn.adinall.ai.cases.")) continue;
                Class<?> type=Class.forName(className);
                for(Constructor<?> constructor:type.getConstructors()) for(Parameter parameter:constructor.getParameters()) {
                    if(parameter.getType().getName().startsWith("cn.adinall.ai.domain.")) external.add(parameter.getType());
                }
                for(Field field:type.getDeclaredFields()) if(field.isAnnotationPresent(jakarta.annotation.Resource.class)) external.add(field.getType());
            }
            for(Class<?> type:external) registerMock(context,type);
            context.registerBean(Clock.class,Clock::systemUTC);
            context.registerBean(ICurrentActor.class,() -> () -> new AccountPrincipalVO(7,"u7","test"));
            context.refresh();
            assertNotNull(context.getBean(cn.adinall.ai.cases.conversation.IConversationCase.class));
            assertNotNull(context.getBean(cn.adinall.ai.cases.practice.PracticeCase.class));
            assertNotNull(context.getBean(cn.adinall.ai.cases.teaching.GuidedReadingCase.class));
        }
    }
    private static <T> void registerMock(AnnotationConfigApplicationContext context,Class<T> type) {
        context.registerBean(type,() -> mock(type));
    }
}
