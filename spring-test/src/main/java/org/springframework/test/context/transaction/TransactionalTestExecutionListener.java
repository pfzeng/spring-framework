/*
 * Copyright 2002-2014 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springframework.test.context.transaction;

import java.lang.annotation.Annotation;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.annotation.BeanFactoryAnnotationUtils;
import org.springframework.context.ApplicationContext;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.annotation.AnnotationAttributes;
import org.springframework.test.annotation.Rollback;
import org.springframework.test.context.TestContext;
import org.springframework.test.context.support.AbstractTestExecutionListener;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.annotation.TransactionManagementConfigurer;
import org.springframework.transaction.interceptor.TransactionAttribute;
import org.springframework.transaction.interceptor.TransactionAttributeSource;
import org.springframework.util.Assert;
import org.springframework.util.ReflectionUtils;
import org.springframework.util.StringUtils;

import static org.springframework.core.annotation.AnnotationUtils.*;

/**
 * {@code TestExecutionListener} that provides support for executing tests
 * within transactions by honoring the
 * {@link org.springframework.transaction.annotation.Transactional @Transactional}
 * annotation. Expects a {@link PlatformTransactionManager} bean to be defined in the
 * Spring {@link ApplicationContext} for the test.
 *
 * <p>Changes to the database during a test that is run with {@code @Transactional}
 * will be run within a transaction that will, by default, be automatically
 * <em>rolled back</em> after completion of the test. Test methods that are
 * <em>not</em> annotated with {@code @Transactional} (at the class or method
 * level) will not be run within a transaction. Furthermore, test methods
 * that <em>are</em> annotated with {@code @Transactional} but have the
 * {@link org.springframework.transaction.annotation.Transactional#propagation propagation}
 * type set to
 * {@link org.springframework.transaction.annotation.Propagation#NOT_SUPPORTED NOT_SUPPORTED}
 * will not be run within a transaction.
 *
 * <p>Transactional commit and rollback behavior can be configured via the
 * class-level {@link TransactionConfiguration @TransactionConfiguration} and
 * method-level {@link Rollback @Rollback} annotations.
 *
 * <p>In case there are multiple instances of {@code PlatformTransactionManager}
 * within the test's {@code ApplicationContext}, {@code @TransactionConfiguration}
 * supports configuring the bean name of the {@code PlatformTransactionManager}
 * that should be used to drive transactions. Alternatively, a <em>qualifier</em>
 * may be declared via {@link Transactional#value} or
 * {@link TransactionManagementConfigurer} can be implemented by an
 * {@link org.springframework.context.annotation.Configuration @Configuration}
 * class. See {@link TestContextTransactionUtils#retrieveTransactionManager()}
 * for details on the algorithm used to look up a transaction manager in
 * the test's {@code ApplicationContext}.
 *
 * <p>When executing transactional tests, it is sometimes useful to be able to
 * execute certain <em>set up</em> or <em>tear down</em> code outside of a
 * transaction. {@code TransactionalTestExecutionListener} provides such
 * support for methods annotated with
 * {@link BeforeTransaction @BeforeTransaction} and
 * {@link AfterTransaction @AfterTransaction}.
 *
 * @author Sam Brannen
 * @author Juergen Hoeller
 * @since 2.5
 * @see TransactionConfiguration
 * @see TransactionManagementConfigurer
 * @see org.springframework.transaction.annotation.Transactional
 * @see org.springframework.test.annotation.Rollback
 * @see BeforeTransaction
 * @see AfterTransaction
 * @see TestTransaction
 */
public class TransactionalTestExecutionListener extends AbstractTestExecutionListener {

	private static final Log logger = LogFactory.getLog(TransactionalTestExecutionListener.class);

	private static final String DEFAULT_TRANSACTION_MANAGER_NAME = (String) getDefaultValue(
		TransactionConfiguration.class, "transactionManager");

	private static final Boolean DEFAULT_DEFAULT_ROLLBACK = (Boolean) getDefaultValue(TransactionConfiguration.class,
		"defaultRollback");

	protected final TransactionAttributeSource attributeSource = new AnnotationTransactionAttributeSource();

	private TransactionConfigurationAttributes configurationAttributes;


	/**
	 * If the test method of the supplied {@linkplain TestContext test context}
	 * is configured to run within a transaction, this method will run
	 * {@link BeforeTransaction @BeforeTransaction} methods and start a new
	 * transaction.
	 * <p>Note that if a {@code @BeforeTransaction} method fails, any remaining
	 * {@code @BeforeTransaction} methods will not be invoked, and a transaction
	 * will not be started.
	 * @see org.springframework.transaction.annotation.Transactional
	 * @see #getTransactionManager(TestContext, String)
	 */
	@Override
	public void beforeTestMethod(final TestContext testContext) throws Exception {
		final Method testMethod = testContext.getTestMethod();
		final Class<?> testClass = testContext.getTestClass();
		Assert.notNull(testMethod, "The test method of the supplied TestContext must not be null");

		TransactionContext txContext = TransactionContextHolder.removeCurrentTransactionContext();
		if (txContext != null) {
			throw new IllegalStateException("Cannot start a new transaction without ending the existing transaction.");
		}

		PlatformTransactionManager tm = null;
		TransactionAttribute transactionAttribute = this.attributeSource.getTransactionAttribute(testMethod, testClass);

		if (transactionAttribute != null) {
			transactionAttribute = TestContextTransactionUtils.createDelegatingTransactionAttribute(testContext,
				transactionAttribute);

			if (logger.isDebugEnabled()) {
				logger.debug("Explicit transaction definition [" + transactionAttribute + "] found for test context "
						+ testContext);
			}

			if (transactionAttribute.getPropagationBehavior() == TransactionDefinition.PROPAGATION_NOT_SUPPORTED) {
				return;
			}

			tm = getTransactionManager(testContext, transactionAttribute.getQualifier());
		}

		if (tm != null) {
			txContext = new TransactionContext(testContext, tm, transactionAttribute, isRollback(testContext));
			runBeforeTransactionMethods(testContext);
			txContext.startTransaction();
			TransactionContextHolder.setCurrentTransactionContext(txContext);
		}
	}

	/**
	 * If a transaction is currently active for the supplied
	 * {@linkplain TestContext test context}, this method will end the transaction
	 * and run {@link AfterTransaction @AfterTransaction} methods.
	 * <p>{@code @AfterTransaction} methods are guaranteed to be invoked even if
	 * an error occurs while ending the transaction.
	 */
	@Override
	public void afterTestMethod(TestContext testContext) throws Exception {
		Method testMethod = testContext.getTestMethod();
		Assert.notNull(testMethod, "The test method of the supplied TestContext must not be null");

		TransactionContext txContext = TransactionContextHolder.removeCurrentTransactionContext();
		// If there was (or perhaps still is) a transaction...
		if (txContext != null) {
			TransactionStatus transactionStatus = txContext.getTransactionStatus();
			try {
				// If the transaction is still active...
				if ((transactionStatus != null) && !transactionStatus.isCompleted()) {
					txContext.endTransaction();
				}
			}
			finally {
				runAfterTransactionMethods(testContext);
			}
		}
	}

	/**
	 * Run all {@link BeforeTransaction @BeforeTransaction} methods for the
	 * specified {@link TestContext test context}. If one of the methods fails,
	 * however, the caught exception will be rethrown in a wrapped
	 * {@link RuntimeException}, and the remaining methods will <strong>not</strong>
	 * be given a chance to execute.
	 * @param testContext the current test context
	 */
	protected void runBeforeTransactionMethods(TestContext testContext) throws Exception {
		try {
			List<Method> methods = getAnnotatedMethods(testContext.getTestClass(), BeforeTransaction.class);
			Collections.reverse(methods);
			for (Method method : methods) {
				if (logger.isDebugEnabled()) {
					logger.debug("Executing @BeforeTransaction method [" + method + "] for test context " + testContext);
				}
				method.invoke(testContext.getTestInstance());
			}
		}
		catch (InvocationTargetException ex) {
			logger.error("Exception encountered while executing @BeforeTransaction methods for test context "
					+ testContext + ".", ex.getTargetException());
			ReflectionUtils.rethrowException(ex.getTargetException());
		}
	}

	/**
	 * Run all {@link AfterTransaction @AfterTransaction} methods for the
	 * specified {@link TestContext test context}. If one of the methods fails,
	 * the caught exception will be logged as an error, and the remaining
	 * methods will be given a chance to execute. After all methods have
	 * executed, the first caught exception, if any, will be rethrown.
	 * @param testContext the current test context
	 */
	protected void runAfterTransactionMethods(TestContext testContext) throws Exception {
		Throwable afterTransactionException = null;

		List<Method> methods = getAnnotatedMethods(testContext.getTestClass(), AfterTransaction.class);
		for (Method method : methods) {
			try {
				if (logger.isDebugEnabled()) {
					logger.debug("Executing @AfterTransaction method [" + method + "] for test context " + testContext);
				}
				method.invoke(testContext.getTestInstance());
			}
			catch (InvocationTargetException ex) {
				Throwable targetException = ex.getTargetException();
				if (afterTransactionException == null) {
					afterTransactionException = targetException;
				}
				logger.error("Exception encountered while executing @AfterTransaction method [" + method
						+ "] for test context " + testContext, targetException);
			}
			catch (Exception ex) {
				if (afterTransactionException == null) {
					afterTransactionException = ex;
				}
				logger.error("Exception encountered while executing @AfterTransaction method [" + method
						+ "] for test context " + testContext, ex);
			}
		}

		if (afterTransactionException != null) {
			ReflectionUtils.rethrowException(afterTransactionException);
		}
	}

	/**
	 * Get the {@link PlatformTransactionManager transaction manager} to use
	 * for the supplied {@linkplain TestContext test context} and {@code qualifier}.
	 * <p>Delegates to {@link #getTransactionManager(TestContext)} if the
	 * supplied {@code qualifier} is {@code null} or empty.
	 * @param testContext the test context for which the transaction manager
	 * should be retrieved
	 * @param qualifier the qualifier for selecting between multiple bean matches;
	 * may be {@code null} or empty
	 * @return the transaction manager to use, or {@code null} if not found
	 * @throws BeansException if an error occurs while retrieving the transaction manager
	 * @see #getTransactionManager(TestContext)
	 */
	protected PlatformTransactionManager getTransactionManager(TestContext testContext, String qualifier) {
		// look up by type and qualifier from @Transactional
		if (StringUtils.hasText(qualifier)) {
			try {
				// Use autowire-capable factory in order to support extended qualifier
				// matching (only exposed on the internal BeanFactory, not on the
				// ApplicationContext).
				BeanFactory bf = testContext.getApplicationContext().getAutowireCapableBeanFactory();

				return BeanFactoryAnnotationUtils.qualifiedBeanOfType(bf, PlatformTransactionManager.class, qualifier);
			}
			catch (RuntimeException ex) {
				if (logger.isWarnEnabled()) {
					logger.warn(
						String.format(
							"Caught exception while retrieving transaction manager with qualifier '%s' for test context %s",
							qualifier, testContext), ex);
				}
				throw ex;
			}
		}

		// else
		return getTransactionManager(testContext);
	}

	/**
	 * Get the {@link PlatformTransactionManager transaction manager} to use
	 * for the supplied {@link TestContext test context}.
	 * <p>The default implementation simply delegates to 
	 * {@link TestContextTransactionUtils#retrieveTransactionManager}.
	 * @param testContext the test context for which the transaction manager
	 * should be retrieved
	 * @return the transaction manager to use, or {@code null} if not found
	 * @throws BeansException if an error occurs while retrieving an explicitly
	 * named transaction manager
	 * @see #getTransactionManager(TestContext, String)
	 */
	protected PlatformTransactionManager getTransactionManager(TestContext testContext) {
		String tmName = retrieveConfigurationAttributes(testContext).getTransactionManagerName();
		return TestContextTransactionUtils.retrieveTransactionManager(testContext, tmName);
	}

	/**
	 * Determine whether or not to rollback transactions by default for the
	 * supplied {@link TestContext test context}.
	 * @param testContext the test context for which the default rollback flag
	 * should be retrieved
	 * @return the <em>default rollback</em> flag for the supplied test context
	 * @throws Exception if an error occurs while determining the default rollback flag
	 */
	protected final boolean isDefaultRollback(TestContext testContext) throws Exception {
		return retrieveConfigurationAttributes(testContext).isDefaultRollback();
	}

	/**
	 * Determine whether or not to rollback transactions for the supplied
	 * {@link TestContext test context} by taking into consideration the
	 * {@link #isDefaultRollback(TestContext) default rollback} flag and a
	 * possible method-level override via the {@link Rollback} annotation.
	 * @param testContext the test context for which the rollback flag
	 * should be retrieved
	 * @return the <em>rollback</em> flag for the supplied test context
	 * @throws Exception if an error occurs while determining the rollback flag
	 */
	protected final boolean isRollback(TestContext testContext) throws Exception {
		boolean rollback = isDefaultRollback(testContext);
		Rollback rollbackAnnotation = findAnnotation(testContext.getTestMethod(), Rollback.class);
		if (rollbackAnnotation != null) {
			boolean rollbackOverride = rollbackAnnotation.value();
			if (logger.isDebugEnabled()) {
				logger.debug(String.format(
					"Method-level @Rollback(%s) overrides default rollback [%s] for test context %s.",
					rollbackOverride, rollback, testContext));
			}
			rollback = rollbackOverride;
		}
		else {
			if (logger.isDebugEnabled()) {
				logger.debug(String.format(
					"No method-level @Rollback override: using default rollback [%s] for test context %s.", rollback,
					testContext));
			}
		}
		return rollback;
	}

	/**
	 * Gets all superclasses of the supplied {@link Class class}, including the
	 * class itself. The ordering of the returned list will begin with the
	 * supplied class and continue up the class hierarchy, excluding {@link Object}.
	 * <p>Note: This code has been borrowed from
	 * {@link org.junit.internal.runners.TestClass#getSuperClasses(Class)} and
	 * adapted.
	 * @param clazz the class for which to retrieve the superclasses
	 * @return all superclasses of the supplied class, excluding {@code Object}
	 */
	private List<Class<?>> getSuperClasses(Class<?> clazz) {
		List<Class<?>> results = new ArrayList<Class<?>>();
		Class<?> current = clazz;
		while (current != null && !current.equals(Object.class)) {
			results.add(current);
			current = current.getSuperclass();
		}
		return results;
	}

	/**
	 * Gets all methods in the supplied {@link Class class} and its superclasses
	 * which are annotated with the supplied {@code annotationType} but
	 * which are not <em>shadowed</em> by methods overridden in subclasses.
	 * <p>Note: This code has been borrowed from
	 * {@link org.junit.internal.runners.TestClass#getAnnotatedMethods(Class)}
	 * and adapted.
	 * @param clazz the class for which to retrieve the annotated methods
	 * @param annotationType the annotation type for which to search
	 * @return all annotated methods in the supplied class and its superclasses
	 */
	private List<Method> getAnnotatedMethods(Class<?> clazz, Class<? extends Annotation> annotationType) {
		List<Method> results = new ArrayList<Method>();
		for (Class<?> current : getSuperClasses(clazz)) {
			for (Method method : current.getDeclaredMethods()) {
				Annotation annotation = getAnnotation(method, annotationType);
				if (annotation != null && !isShadowed(method, results)) {
					results.add(method);
				}
			}
		}
		return results;
	}

	/**
	 * Determine if the supplied {@link Method method} is <em>shadowed</em> by
	 * a method in the supplied {@link List list} of previous methods.
	 * <p>Note: This code has been borrowed from
	 * {@link org.junit.internal.runners.TestClass#isShadowed(Method, List)}.
	 * @param method the method to check for shadowing
	 * @param previousMethods the list of methods which have previously been processed
	 * @return {@code true} if the supplied method is shadowed by a
	 * method in the {@code previousMethods} list
	 */
	private boolean isShadowed(Method method, List<Method> previousMethods) {
		for (Method each : previousMethods) {
			if (isShadowed(method, each)) {
				return true;
			}
		}
		return false;
	}

	/**
	 * Determine if the supplied {@link Method current method} is <em>shadowed</em>
	 * by a {@link Method previous method}.
	 * <p>Note: This code has been borrowed from
	 * {@link org.junit.internal.runners.TestClass#isShadowed(Method, Method)}.
	 * @param current the current method
	 * @param previous the previous method
	 * @return {@code true} if the previous method shadows the current one
	 */
	private boolean isShadowed(Method current, Method previous) {
		if (!previous.getName().equals(current.getName())) {
			return false;
		}
		if (previous.getParameterTypes().length != current.getParameterTypes().length) {
			return false;
		}
		for (int i = 0; i < previous.getParameterTypes().length; i++) {
			if (!previous.getParameterTypes()[i].equals(current.getParameterTypes()[i])) {
				return false;
			}
		}
		return true;
	}

	/**
	 * Retrieves the {@link TransactionConfigurationAttributes} for the
	 * specified {@link Class class} which may optionally declare or inherit
	 * {@link TransactionConfiguration @TransactionConfiguration}. If
	 * {@code @TransactionConfiguration} is not present for the supplied
	 * class, the <em>default values</em> for attributes defined in
	 * {@code @TransactionConfiguration} will be used instead.
	 * @param testContext the test context for which the configuration
	 * attributes should be retrieved
	 * @return the TransactionConfigurationAttributes instance for this listener,
	 * potentially cached
	 */
	TransactionConfigurationAttributes retrieveConfigurationAttributes(TestContext testContext) {
		if (this.configurationAttributes == null) {
			Class<?> clazz = testContext.getTestClass();

			AnnotationAttributes annAttrs = AnnotatedElementUtils.getAnnotationAttributes(clazz,
				TransactionConfiguration.class.getName());
			if (logger.isDebugEnabled()) {
				logger.debug(String.format("Retrieved @TransactionConfiguration attributes [%s] for test class [%s].",
					annAttrs, clazz));
			}

			String transactionManagerName;
			boolean defaultRollback;
			if (annAttrs != null) {
				transactionManagerName = annAttrs.getString("transactionManager");
				defaultRollback = annAttrs.getBoolean("defaultRollback");
			}
			else {
				transactionManagerName = DEFAULT_TRANSACTION_MANAGER_NAME;
				defaultRollback = DEFAULT_DEFAULT_ROLLBACK;
			}

			TransactionConfigurationAttributes configAttributes = new TransactionConfigurationAttributes(
				transactionManagerName, defaultRollback);
			if (logger.isDebugEnabled()) {
				logger.debug(String.format("Retrieved TransactionConfigurationAttributes %s for class [%s].",
					configAttributes, clazz));
			}
			this.configurationAttributes = configAttributes;
		}
		return this.configurationAttributes;
	}

}
