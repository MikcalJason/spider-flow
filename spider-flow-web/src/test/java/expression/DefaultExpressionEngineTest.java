package expression;

import com.google.common.collect.Lists;
import org.junit.Assert;
import org.junit.Test;
import org.spiderflow.ExpressionEngine;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;


public class DefaultExpressionEngineTest extends BaseIntegrationTest {

    @Autowired
    ExpressionEngine defaultExpressionEngine;

    @Test
    public void should_return_true_when_execute_add() {
        Map map = new HashMap();
        map.put("resp1", "abc");
        map.put("resp2", "efd");
        Assert.assertEquals("abcefd", defaultExpressionEngine.execute("${resp1+resp2}", map));
    }

    @Test
    public void should_return_true_when_execute_java_method() {
        Map map = new HashMap();
        map.put("resp1", "abc-efd");
        Object obj = defaultExpressionEngine.execute("${resp1.split('-')}", map);
        System.out.println();
    }

}
