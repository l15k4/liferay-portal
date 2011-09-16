
package com.liferay.portal.jcr.modeshape;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

import javax.jcr.RepositoryException;
import javax.jcr.Session;

import org.modeshape.jcr.JcrRepository;
import org.modeshape.jcr.JcrRepositoryFactory;

import com.liferay.portal.jcr.JCRFactory;
import com.liferay.portal.kernel.log.Log;
import com.liferay.portal.kernel.log.LogFactoryUtil;
import com.liferay.portal.kernel.util.FileUtil;
import com.liferay.portal.kernel.util.SystemProperties;
import com.liferay.portal.kernel.util.Time;

/**
 * @author Michael Young
 */
public class JCRFactoryImpl implements JCRFactory {

	public Session createSession(String workspaceName)
		throws RepositoryException {

		/**
		 * Credentials credentials = new AnonymousCredentials(); // in modeshape
		 * version 2.5+; this would be in configuration :
		 * <anonymousUserRoles jcr:PrimaryType="mode:option"
		 * mode:value="admin,readwrite" />
		 */

		Session session = null;

		if (repository == null)
			prepare();

		try {
			session = repository.login(workspaceName);
		}
		catch (RepositoryException re) {
			_log.error("Could not login to the workspace " + workspaceName);

			throw re;
		}

		return session;
	}

	public void initialize()
		throws RepositoryException {

		Session session = null;

		try {
			session = createSession(null);
		}
		catch (RepositoryException re) {
			_log.error("Could not initialize Modeshape");

			throw re;
		}
		finally {
			if (session != null) {
				session.logout();
			}
		}

		_initialized = true;
	}

	public void prepare()
		throws RepositoryException {

		try {
			File repositoryRoot = new File(JCRFactoryImpl.REPOSITORY_ROOT);
			File config = new File(JCRFactoryImpl.CONFIG_FILE_PATH);

			if (config.exists()) {
				return;
			}

			repositoryRoot.mkdirs();

			File tempFile =
				new File(SystemProperties.get(SystemProperties.TMP_DIR) +
					File.separator + Time.getTimestamp());

			String repositoryXmlPath =
				"com/liferay/portal/jcr/modeshape/dependencies/"
					+ "repository-ext.xml";

			ClassLoader classLoader = getClass().getClassLoader();

			if (classLoader.getResource(repositoryXmlPath) == null) {
				repositoryXmlPath =
					"com/liferay/portal/jcr/modeshape/dependencies/"
						+ "repository.xml";
			}

			FileUtil.write(
				tempFile, classLoader.getResourceAsStream(repositoryXmlPath));

			FileUtil.copyFile(tempFile, new File(
				JCRFactoryImpl.CONFIG_FILE_PATH));

			tempFile.delete();

		}
		catch (IOException ioe) {
			_log.error("Could not prepare Modeshape directory");

			throw new RepositoryException(ioe);
		}
	}

	public void shutdown() {

		if (_initialized) {
			repositoryFactory.shutdown();
		}

		_initialized = false;
	}

	protected JCRFactoryImpl()
		throws Exception {

		try {

			File config = new File(JCRFactoryImpl.CONFIG_FILE_PATH);
			if (!config.exists())
				prepare();

			repositoryFactory = new JcrRepositoryFactory();
			Map<String, String> params = new HashMap<String, String>();
			params.put(JcrRepositoryFactory.URL, "file://" + CONFIG_FILE_PATH);
			// repo name doesn't have to be specified if only one is defined in
			// configuration
			params.put(
				JcrRepositoryFactory.REPOSITORY_NAME_PARAM, "inMemoryRepo");
			repository =
				(JcrRepository) repositoryFactory.getRepository(params);

		}
		catch (Exception e) {
			_log.error("Problem initializing Modeshape JCR.", e);

			throw e;
		}

		if (_log.isInfoEnabled()) {
			_log.info("Modeshape JCR intialized with config file path " +
				CONFIG_FILE_PATH + " and repository home " + REPOSITORY_HOME);
		}
	}

	private static Log _log = LogFactoryUtil.getLog(JCRFactoryImpl.class);

	private boolean _initialized;
	private JcrRepositoryFactory repositoryFactory;
	private JcrRepository repository;

}
