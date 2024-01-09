package expression;

import org.junit.runner.RunWith;
import org.spiderflow.SpiderApplication;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.junit4.SpringRunner;

/**
 * 所有集成测试的基类，这里适合做环境相关的通用操作
 *
 * @author: 严亚民
 * @date 2022-09-16 13:52
 */

@RunWith(SpringRunner.class)
@SpringBootTest(classes = SpiderApplication.class)
public class BaseIntegrationTest {

}
