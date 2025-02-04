package org.spiderflow.core;

import com.alibaba.ttl.TtlRunnable;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.math.NumberUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spiderflow.concurrent.*;
import org.spiderflow.concurrent.SpiderFlowThreadPoolExecutor.SubThreadPoolExecutor;
import org.spiderflow.context.SpiderContext;
import org.spiderflow.context.SpiderContextHolder;
import org.spiderflow.core.executor.shape.LoopExecutor;
import org.spiderflow.core.model.SpiderFlow;
import org.spiderflow.core.service.FlowNoticeService;
import org.spiderflow.core.utils.ExecutorsUtils;
import org.spiderflow.core.utils.ExpressionUtils;
import org.spiderflow.core.utils.SpiderFlowUtils;
import org.spiderflow.enums.FlowNoticeType;
import org.spiderflow.executor.ShapeExecutor;
import org.spiderflow.listener.SpiderListener;
import org.spiderflow.model.SpiderNode;
import org.spiderflow.model.SpiderOutput;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import java.lang.reflect.Array;
import java.util.*;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 爬虫的核心类
 *
 * @author jmxd
 */
@Component
public class Spider {

	@Autowired(required = false)
	private List<SpiderListener> listeners;

	@Value("${spider.thread.max:64}")
	private Integer totalThreads;

	@Value("${spider.thread.default:8}")
	private Integer defaultThreads;

	@Value("${spider.detect.dead-cycle:5000}")
	private Integer deadCycle;
	
	@Autowired
	private FlowNoticeService flowNoticeService;

	public static SpiderFlowThreadPoolExecutor executorInstance;

	private static final String ATOMIC_DEAD_CYCLE = "__atomic_dead_cycle";

	private static Logger logger = LoggerFactory.getLogger(Spider.class);

	@PostConstruct
	private void init() {
		executorInstance = new SpiderFlowThreadPoolExecutor(totalThreads);
	}

	public List<SpiderOutput> run(SpiderFlow spiderFlow, SpiderContext context, Map<String, Object> variables) {
		if (variables == null) {
			variables = new HashMap<>();
		}
		SpiderNode root = SpiderFlowUtils.loadXMLFromString(spiderFlow.getXml());
		// 流程开始通知
		flowNoticeService.sendFlowNotice(spiderFlow, FlowNoticeType.startNotice);
		executeRoot(root, context, variables);
		// 流程结束通知
		flowNoticeService.sendFlowNotice(spiderFlow, FlowNoticeType.endNotice);
		return context.getOutputs();
	}

	public List<SpiderOutput> run(SpiderFlow spiderFlow, SpiderContext context) {
		return run(spiderFlow, context, new HashMap<>());
	}

	public void runWithTest(SpiderNode root, SpiderContext context) {
		//将上下文存到ThreadLocal里，以便后续使用
		SpiderContextHolder.set(context);
		//死循环检测的计数器（死循环检测只在测试时有效）
		AtomicInteger executeCount = new AtomicInteger(0);
		//存入到上下文中，以供后续检测
		context.put(ATOMIC_DEAD_CYCLE, executeCount);
		//执行根节点
		executeRoot(root, context, new HashMap<>());
		//当爬虫任务执行完毕时,判断是否超过预期
		if (executeCount.get() > deadCycle) {
			logger.error("检测到可能出现死循环,测试终止");
		} else {
			logger.info("测试完毕！");
		}
		//将上下文从ThreadLocal移除，防止内存泄漏
		SpiderContextHolder.remove();
	}

	/**
	 * 执行根节点
	 */
	private void executeRoot(SpiderNode root, SpiderContext context, Map<String, Object> variables) {
		//获取当前流程执行线程数
		int nThreads = NumberUtils.toInt(root.getStringJsonValue(ShapeExecutor.THREAD_COUNT), defaultThreads);
		String strategy = root.getStringJsonValue("submit-strategy");
		ThreadSubmitStrategy submitStrategy;
		//选择提交策略，这里一定要使用new,不能与其他实例共享
		if("linked".equalsIgnoreCase(strategy)){
			submitStrategy = new LinkedThreadSubmitStrategy();
		}else if("child".equalsIgnoreCase(strategy)){
			submitStrategy = new ChildPriorThreadSubmitStrategy();
		}else if("parent".equalsIgnoreCase(strategy)){
			submitStrategy = new ParentPriorThreadSubmitStrategy();
		}else{
			submitStrategy = new RandomThreadSubmitStrategy();
		}
		//创建子线程池，采用一父多子的线程池,子线程数不能超过总线程数（超过时进入队列等待）,+1是因为会占用一个线程用来调度执行下一级
		SubThreadPoolExecutor pool = executorInstance.createSubThreadPoolExecutor(Math.max(nThreads,1) + 1,submitStrategy);
		context.setRootNode(root);
		context.setThreadPool(pool);
		//触发监听器
		if (listeners != null) {
			listeners.forEach(listener -> listener.beforeStart(context));
		}
		Comparator<SpiderNode> comparator = submitStrategy.comparator();
		//启动一个线程开始执行任务,并监听其结束并执行下一级
		Future<?> f = pool.submitAsync(TtlRunnable.get(() -> {
			try {
				//执行具体节点
				Spider.this.executeNode(null, root, context, variables);
				Queue<Future<?>> queue = context.getFutureQueue();
				//循环从队列中获取Future,直到队列为空结束,当任务完成时，则执行下一级
				while (!queue.isEmpty()) {
					try {
						//TODO 这里应该是取出最先执行完毕的任务
						Optional<Future<?>> max = queue.stream().filter(Future::isDone).max((o1, o2) -> {
							try {
								return comparator.compare(((SpiderTask) o1.get()).node, ((SpiderTask) o2.get()).node);
							} catch (InterruptedException | ExecutionException e) {
							}
							return 0;

						});
						if (max.isPresent()) {	//判断任务是否完成
							queue.remove(max.get());
							if (context.isRunning()) {	//检测是否运行中(当在页面中点击"停止"时,此值为false,其余为true)
								SpiderTask task = (SpiderTask) max.get().get();
								task.node.decrement();	//任务执行完毕,计数器减一(该计数器是给Join节点使用)
								if (task.executor.allowExecuteNext(task.node, context, task.variables)) {	//判断是否允许执行下一级
									logger.debug("执行节点[{}:{}]完毕", task.node.getNodeName(), task.node.getNodeId());
									//执行下一级
									Spider.this.executeNextNodes(task.node, context, task.variables);
								} else {
									logger.debug("执行节点[{}:{}]完毕，忽略执行下一节点", task.node.getNodeName(), task.node.getNodeId());
								}
							}
						}
						//睡眠1ms,让出cpu
						Thread.sleep(1);
					} catch (InterruptedException ignored) {
					} catch (Throwable t){
						logger.error("程序发生异常",t);
					}
				}
				//等待线程池结束
				pool.awaitTermination();
			} finally {
				//触发监听器
				if (listeners != null) {
					listeners.forEach(listener -> listener.afterEnd(context));
				}
			}
		}), null, root);
		try {
			f.get();	//阻塞等待所有任务执行完毕
		} catch (InterruptedException | ExecutionException ignored) {}
	}

	/**
	 * 执行下一级节点
	 */
	private void executeNextNodes(SpiderNode node, SpiderContext context, Map<String, Object> variables) {
		List<SpiderNode> nextNodes = node.getNextNodes();
		if (nextNodes != null) {
			for (SpiderNode nextNode : nextNodes) {
				executeNode(node, nextNode, context, variables);
			}
		}
	}

	/**
	 * 执行节点
	 */
	public void executeNode(SpiderNode fromNode, SpiderNode node, SpiderContext context, Map<String, Object> variables) {
		String shape = node.getStringJsonValue("shape");
		if (StringUtils.isBlank(shape)) {
			executeNextNodes(node, context, variables);
			return;
		}
		//判断箭头上的条件，如果不成立则不执行
		if (!executeCondition(fromNode, node, variables, context)) {
			return;
		}
		logger.debug("执行节点[{}:{}]", node.getNodeName(), node.getNodeId());
		//找到对应的执行器
		ShapeExecutor executor = ExecutorsUtils.get(shape);
		if (executor == null) {
			logger.error("执行失败,找不到对应的执行器:{}", shape);
			context.setRunning(false);
		}
		int loopCount = 1;	//循环次数默认为1,如果节点有循环属性且填了循环次数/集合,则取出循环次数
		int loopStart = 0;	//循环起始位置
		int loopEnd = 1;	//循环结束位置
		String loopCountStr = node.getStringJsonValue(ShapeExecutor.LOOP_COUNT);
		Object loopArray = null;
		boolean isLoop = false;
		if (isLoop = StringUtils.isNotBlank(loopCountStr)) {
			try {
				loopArray = ExpressionUtils.execute(loopCountStr, variables);
				if(loopArray == null){
					loopCount = 0;
				}else if(loopArray instanceof Collection){
					loopCount = ((Collection)loopArray).size();
					loopArray = ((Collection)loopArray).toArray();
				}else if(loopArray.getClass().isArray()){
					loopCount = Array.getLength(loopArray);
				}else{
					loopCount = NumberUtils.toInt(loopArray.toString(),0);
					loopArray = null;
				}
				loopEnd = loopCount;
				if(loopCount > 0){
					loopStart = Math.max(NumberUtils.toInt(node.getStringJsonValue(LoopExecutor.LOOP_START), 0),0);
					int end = NumberUtils.toInt(node.getStringJsonValue(LoopExecutor.LOOP_END), -1);
					if(end >=0){
						loopEnd = Math.min(end,loopEnd);
					}else{
						loopEnd = Math.max(loopEnd + end + 1,0);
					}
				}
				logger.info("获取循环次数{}={}", loopCountStr, loopCount);
			} catch (Throwable t) {
				loopCount = 0;
				logger.error("获取循环次数失败,异常信息：{}", t);
			}
		}
		if (loopCount > 0) {
			//获取循环下标的变量名称
			String loopVariableName = node.getStringJsonValue(ShapeExecutor.LOOP_VARIABLE_NAME);
			String loopItem = node.getStringJsonValue(LoopExecutor.LOOP_ITEM,"item");
			List<SpiderTask> tasks = new ArrayList<>();
			for (int i = loopStart; i < loopEnd; i++) {
				node.increment();	//节点执行次数+1(后续Join节点使用)
				if (context.isRunning()) {
					Map<String, Object> nVariables = new HashMap<>();
					// 判断是否需要传递变量
					if(fromNode == null || node.isTransmitVariable(fromNode.getNodeId())){
						nVariables.putAll(variables);
					}
					if(isLoop){
						// 存入下标变量
						if (!StringUtils.isBlank(loopVariableName)) {
							nVariables.put(loopVariableName, i);
						}
						// 存入item
						nVariables.put(loopItem,loopArray == null ? i : Array.get(loopArray, i));
					}
					tasks.add(new SpiderTask(TtlRunnable.get(() -> {
						if (context.isRunning()) {
							try {
								//死循环检测，当执行节点次数大于阈值时，结束本次测试
								AtomicInteger executeCount = context.get(ATOMIC_DEAD_CYCLE);
								if (executeCount != null && executeCount.incrementAndGet() > deadCycle) {
									context.setRunning(false);
									return;
								}
								logger.debug("开始执行executor:{}",executor.supportShape());
								//执行节点具体逻辑
								executor.execute(node, context, nVariables);
								//当未发生异常时，移除ex变量
								nVariables.remove("ex");
							} catch (Throwable t) {
								nVariables.put("ex", t);
								logger.error("执行节点[{}:{}]出错,异常信息：{}", node.getNodeName(), node.getNodeId(), t);
							}
						}
					}), node, nVariables, executor));
				}
			}
			LinkedBlockingQueue<Future<?>> futureQueue = context.getFutureQueue();
			for (SpiderTask task : tasks) {
				if(executor.isThread()){	//判断节点是否是异步运行
					//提交任务至线程池中,并将Future添加到队列末尾
					futureQueue.add(context.getThreadPool().submitAsync(task.runnable, task, node));
				}else{
					FutureTask<SpiderTask> futureTask = new FutureTask<>(task.runnable, task);
					futureTask.run();
					futureQueue.add(futureTask);
				}
			}
		}
	}

	/**
	 *	判断箭头上的表达式是否成立
	 */
	private boolean executeCondition(SpiderNode fromNode, SpiderNode node, Map<String, Object> variables, SpiderContext context) {
		if (fromNode != null) {
			boolean hasException = variables.get("ex") != null;
			String exceptionFlow = node.getExceptionFlow(fromNode.getNodeId());
			//当出现异常流转 : 1
			//未出现异常流转 : 2
			if(("1".equalsIgnoreCase(exceptionFlow) && !hasException) || ("2".equalsIgnoreCase(exceptionFlow) && hasException)){
				return false;
			}
			String condition = node.getCondition(fromNode.getNodeId());
			if (StringUtils.isNotBlank(condition)) { // 判断是否有条件
				Object result = null;
				try {
					result = ExpressionUtils.execute(condition, variables);
				} catch (Exception e) {
					logger.error("判断{}出错,异常信息：{}", condition, e);
				}
				if (result != null) {
					boolean isContinue = "true".equals(result) || Objects.equals(result, true);
					logger.debug("判断{}={}", condition, isContinue);
					return isContinue;
				}
				return false;
			}
		}
		return true;
	}

	class SpiderTask{

		Runnable runnable;

		SpiderNode node;

		Map<String,Object> variables;

		ShapeExecutor executor;

		public SpiderTask(Runnable runnable, SpiderNode node, Map<String, Object> variables,ShapeExecutor executor) {
			this.runnable = runnable;
			this.node = node;
			this.variables = variables;
			this.executor = executor;
		}
	}
}
