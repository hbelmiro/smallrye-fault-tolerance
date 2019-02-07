package io.smallrye.faulttolerance;

import com.netflix.hystrix.HystrixCommandGroupKey;
import com.netflix.hystrix.HystrixCommandKey;
import com.netflix.hystrix.HystrixCommandProperties;
import com.netflix.hystrix.HystrixObservableCommand;
import io.smallrye.faulttolerance.config.FaultToleranceOperation;
import org.eclipse.microprofile.metrics.Histogram;
import org.eclipse.microprofile.metrics.MetricRegistry;
import org.eclipse.microprofile.metrics.MetricType;
import rx.Observable;

import java.util.concurrent.Callable;
import java.util.concurrent.CompletionStage;

/**
 * mstodo: Header
 *
 * @author Michal Szynkiewicz, michal.l.szynkiewicz@gmail.com
 * <br>
 * Date: 2/7/19
 */
public class CompositeObservableCommand extends HystrixObservableCommand {
    public static HystrixObservableCommand create(Callable<? extends CompletionStage<?>> callable,
                                                     FaultToleranceOperation operation,
                                                     ExecutionContextWithInvocationContext ctx,
                                                     MetricRegistry registry) {
        return new CompositeObservableCommand(callable, operation, ctx, registry);
    }

    private final Callable<? extends CompletionStage> callable;
    private final ExecutionContextWithInvocationContext ctx;
    private final FaultToleranceOperation operation;
    private final MetricRegistry registry;
    private final long queuedAt;


    protected CompositeObservableCommand(Callable<? extends CompletionStage> callable,
                                         FaultToleranceOperation operation,
                                         ExecutionContextWithInvocationContext ctx,
                                         MetricRegistry registry) {
        super(initSetter(operation));
        this.callable = callable;
        this.ctx = ctx;
        this.operation = operation;
        this.registry = registry;
        this.queuedAt = System.nanoTime();
    }

    @Override
    protected Observable construct() {
        if (registry != null && operation.hasBulkhead()) {
            // TODO: in fact, we do not record the time spent in the queue but the time between command creation and command execution
            histogramOf(MetricNames.metricsPrefix(operation.getMethod()) + MetricNames.BULKHEAD_WAITING_DURATION).update(System.nanoTime() - queuedAt);
        }

        return Observable.create(
                subscriber -> {
                    try {
                        CompletionStage<?> stage = callable.call();
                        if (stage == null) {
                            subscriber.onError(new NullPointerException("A method that should return a CompletionStage returned null")); // mstodo better error
                        } else {
                            stage.whenComplete(
                                    (value, error) -> {
                                            if (error == null ) {
                                                subscriber.onNext(value);
                                                subscriber.onCompleted();
                                            } else {
                                                subscriber.onError(error);    // mstodo unwrap the error?
                                                // mstodo investigate how it works
                                            }
                            }
                            );
                        }
                    } catch (Exception e) {
                        subscriber.onError(e);
                    }
                }
        );
    }

    private static HystrixObservableCommand.Setter initSetter(FaultToleranceOperation operation) {
        HystrixCommandProperties.Setter properties = HystrixCommandProperties.Setter();
        HystrixCommandKey commandKey = HystrixCommandKey.Factory
                .asKey(CompositeCommand.class.getSimpleName() + "#" + SimpleCommand.getCommandKey(operation.getMethod()));

        properties.withExecutionIsolationStrategy(HystrixCommandProperties.ExecutionIsolationStrategy.THREAD);
        properties.withFallbackEnabled(false);
        properties.withCircuitBreakerEnabled(false);

        return HystrixObservableCommand.Setter
                .withGroupKey(HystrixCommandGroupKey.Factory.asKey("CompositeCommandGroup"))
                .andCommandKey(commandKey)
                .andCommandPropertiesDefaults(properties);
    }

    // mstodo pull out, it's copied from CompositeCommand
    private Histogram histogramOf(String name) {
        Histogram histogram = registry.getHistograms().get(name);
        if (histogram == null) {
            synchronized (operation) {
                histogram = registry.getHistograms().get(name);
                if (histogram == null) {
                    histogram = registry.histogram(MetricsCollectorFactory.metadataOf(name, MetricType.HISTOGRAM));
                }
            }
        }
        return histogram;
    }
}
