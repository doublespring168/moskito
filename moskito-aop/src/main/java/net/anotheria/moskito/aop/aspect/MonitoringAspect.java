package net.anotheria.moskito.aop.aspect;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

import net.anotheria.moskito.aop.annotation.Monitor;
import net.anotheria.moskito.core.calltrace.CurrentlyTracedCall;
import net.anotheria.moskito.core.calltrace.RunningTraceContainer;
import net.anotheria.moskito.core.calltrace.TraceStep;
import net.anotheria.moskito.core.calltrace.TracedCall;
import net.anotheria.moskito.core.calltrace.TracingUtil;
import net.anotheria.moskito.core.dynamic.OnDemandStatsProducer;
import net.anotheria.moskito.core.journey.Journey;
import net.anotheria.moskito.core.journey.JourneyManagerFactory;
import net.anotheria.moskito.core.predefined.ServiceStats;
import net.anotheria.moskito.core.predefined.ServiceStatsFactory;
import net.anotheria.moskito.core.tracer.Trace;
import net.anotheria.moskito.core.tracer.TracerRepository;
import net.anotheria.moskito.core.tracer.Tracers;
import net.anotheria.moskito.core.util.annotation.AnnotationUtils;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.Signature;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;

/**
 * Aspect used to intercept @MonitorClass annotated classes method calls.
 *
 * @author Vitaliy Zhovtiuk
 * @author lrosenberg
 * @author bvanchuhov
 */
@Aspect
public class MonitoringAspect extends AbstractMoskitoAspect<ServiceStats> {
	/**
	 * Factory constant is needed to prevent continuous reinstantiation of ServiceStatsFactory objects.
	 */
	private static final ServiceStatsFactory FACTORY = new ServiceStatsFactory();

	/**
	 * Common method profiling entry-point.
	 *
	 * @param pjp
	 * 		{@link ProceedingJoinPoint}
	 * @param method
	 * 		{@link Monitor}
	 * @return call result
	 * @throws Throwable
	 * 		in case of error during {@link ProceedingJoinPoint#proceed()}
	 */
	@Around (value = "execution(* *(..)) && (@annotation(method))")
	public Object doProfilingMethod(final ProceedingJoinPoint pjp, final Monitor method) throws Throwable {
		return doProfiling(pjp, method.producerId(), method.subsystem(), method.category());
	}

	/**
	 * Special method profiling entry-point, which allow to fetch {@link Monitor} from one lvl up of method annotation.
	 * Pattern will pre-select 2-nd lvl annotation on method scope.
	 *
	 * @param pjp
	 * 		{@link ProceedingJoinPoint}
	 * @return call result
	 * @throws Throwable
	 * 		in case of error during {@link ProceedingJoinPoint#proceed()}
	 */
	@Around (value = "execution(@(@net.anotheria.moskito.aop.annotation.Monitor *) * *(..)) && !@annotation(net.anotheria.moskito.aop.annotation.DontMonitor)")
	public Object doProfilingMethod(final ProceedingJoinPoint pjp) throws Throwable {
		final Monitor monitor = resolveAnnotation(pjp);
		return monitor == null ? pjp.proceed() : doProfiling(pjp, monitor.producerId(), monitor.subsystem(), monitor.category());
	}

	/**
	 * Common class profiling entry-point.
	 *
	 * @param pjp
	 * 		{@link ProceedingJoinPoint}
	 * @param monitor
	 * 		{@link Monitor}
	 * @return call result
	 * @throws Throwable
	 * 		in case of error during {@link ProceedingJoinPoint#proceed()}
	 */
	@Around (value = "execution(* *.*(..)) && @within(monitor) && !@annotation(net.anotheria.moskito.aop.annotation.DontMonitor)")
	public Object doProfilingClass(ProceedingJoinPoint pjp, Monitor monitor) throws Throwable {
		return doProfiling(pjp, monitor.producerId(), monitor.subsystem(), monitor.category());
	}

	/**
	 * Special class profiling entry-point, which allow to fetch {@link Monitor} from one lvl up of class annotation.
	 * Pattern will pre-select 2-nd lvl annotation on class scope.
	 *
	 * @param pjp
	 * 		{@link ProceedingJoinPoint}
	 * @return call result
	 * @throws Throwable
	 * 		in case of error during {@link ProceedingJoinPoint#proceed()}
	 */
	@Around (value = "execution(* (@(@net.anotheria.moskito.aop.annotation.Monitor *) *).*(..)) && !@annotation(net.anotheria.moskito.aop.annotation.DontMonitor)")
	public Object doProfilingClass(final ProceedingJoinPoint pjp) throws Throwable {
		final Monitor monitor = resolveAnnotation(pjp);
		return monitor == null ? pjp.proceed() : doProfiling(pjp, monitor.producerId(), monitor.subsystem(), monitor.category());
	}

	/**
	 * Perform profiling.
	 *
	 * @param pjp {@link ProceedingJoinPoint}
	 * @param aProducerId id of the producer to use
	 * @param aSubsystem sub-system
	 * @param aCategory category
	 * @return call result
	 * @throws Throwable in case of error during {@link ProceedingJoinPoint#proceed()}
	 */
	private Object doProfiling(ProceedingJoinPoint pjp, String aProducerId, String aSubsystem, String aCategory) throws Throwable {

		OnDemandStatsProducer<ServiceStats> producer = getProducer(pjp, aProducerId, aCategory, aSubsystem, false, FACTORY, true);
		String producerId = producer.getProducerId();

		String caseName = pjp.getSignature().getName();
		ServiceStats defaultStats = producer.getDefaultStats();
		ServiceStats methodStats = producer.getStats(caseName);

		final Object[] args = pjp.getArgs();
		final String method = pjp.getSignature().getName();
		defaultStats.addRequest();
		if (methodStats != null) {
			methodStats.addRequest();
		}
		TracedCall aRunningTrace = RunningTraceContainer.getCurrentlyTracedCall();
		TraceStep currentStep = null;
		CurrentlyTracedCall currentTrace = aRunningTrace.callTraced() ? (CurrentlyTracedCall) aRunningTrace : null;

		TracerRepository tracerRepository = TracerRepository.getInstance();
		boolean tracePassingOfThisProducer = tracerRepository.isTracingEnabledForProducer(producerId);
		Trace trace = null;
		boolean journeyStartedByMe = false;

		//we create trace here already, because we want to reserve a new trace id.
		if (tracePassingOfThisProducer) {
			trace = new Trace();
		}


		if (currentTrace == null && tracePassingOfThisProducer) {
			//ok, we will create a new journey on the fly.
			String journeyCallName = Tracers.getCallName(trace);
			RunningTraceContainer.startTracedCall(journeyCallName);
			journeyStartedByMe = true;

			currentTrace = (CurrentlyTracedCall) RunningTraceContainer.getCurrentlyTracedCall();
		}


		StringBuilder call = null;
		if (currentTrace != null || tracePassingOfThisProducer) {
			call = TracingUtil.buildCall(producerId, method, args, tracePassingOfThisProducer ? Tracers.getCallName(trace) : null);
		}
		if (currentTrace != null) {
			currentStep = currentTrace.startStep(call.toString(), producer);
		}
		long startTime = System.nanoTime();
		Object ret = null;
		try {
			ret = pjp.proceed();
			return ret;
		} catch (InvocationTargetException e) {
			defaultStats.notifyError();
			if (methodStats != null) {
				methodStats.notifyError();
			}
			if (currentStep != null) {
				currentStep.setAborted();
			}
			throw e.getCause();
		} catch (Throwable t) {
			defaultStats.notifyError();
			if (methodStats != null) {
				methodStats.notifyError();
			}
			if (currentStep != null) {
				currentStep.setAborted();
			}
			if (tracePassingOfThisProducer) {
				call.append(" ERR ").append(t.getMessage());
			}
			throw t;
		} finally {
			long exTime = System.nanoTime() - startTime;
			defaultStats.addExecutionTime(exTime);
			if (methodStats != null) {
				methodStats.addExecutionTime(exTime);
			}
			defaultStats.notifyRequestFinished();
			if (methodStats != null) {
				methodStats.notifyRequestFinished();
			}
			if (currentStep != null) {
				currentStep.setDuration(exTime);
				try {
					currentStep.appendToCall(" = " + TracingUtil.parameter2string(ret));
				} catch (Throwable t) {
					currentStep.appendToCall(" = ERR: " + t.getMessage() + " (" + t.getClass() + ')');
				}
			}
			if (currentTrace != null) {
				currentTrace.endStep();
			}

			if (tracePassingOfThisProducer) {
				call.append(" = ").append(TracingUtil.parameter2string(ret));
				trace.setCall(call.toString());
				trace.setDuration(exTime);
				trace.setElements(Thread.currentThread().getStackTrace());

				if (journeyStartedByMe) {
					//now finish the journey.
					Journey myJourney = JourneyManagerFactory.getJourneyManager().getOrCreateJourney(Tracers.getJourneyNameForTracers(producerId));
					myJourney.addUseCase((CurrentlyTracedCall) RunningTraceContainer.endTrace());
					RunningTraceContainer.cleanup();
				}


				tracerRepository.addTracedExecution(producerId, trace);
			}
		}
	}

	/**
	 * Trying to resolve {@link Monitor} annotation first in method annotations scope, then in class scope. Note - method will also check
	 * if {@link Monitor} is Placed to some other annotation as meta!
	 *
	 * @param pjp
	 * 		{@link ProceedingJoinPoint}
	 * @return {@link Monitor} or {@code null}
	 */
	private Monitor resolveAnnotation(final ProceedingJoinPoint pjp) {
		final Signature signature = pjp.getSignature();
		final Class<?> type = signature.getDeclaringType();
		final Method method = (signature instanceof MethodSignature) ? MethodSignature.class.cast(signature).getMethod() :
				null;
		final Monitor methodMonitorAnno = method != null ? AnnotationUtils.findAnnotation(method, Monitor.class) : null;
		if (methodMonitorAnno != null)
			return methodMonitorAnno;
		final Monitor clazzAnno= AnnotationUtils.findAnnotation(type, Monitor.class);
		if(clazzAnno!=null)
			return clazzAnno;
		return AnnotationUtils.findAnnotation(type.getSuperclass(),Monitor.class);
	}


}
