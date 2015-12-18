/*******************************************************************************
 * Copyright (c) 2015 Pivotal, Inc.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *     Pivotal, Inc. - initial API and implementation
 *******************************************************************************/
package org.springframework.ide.eclipse.boot.dash.cloudfoundry;

import static org.springframework.ide.eclipse.boot.dash.model.RunState.DEBUGGING;
import static org.springframework.ide.eclipse.boot.dash.model.RunState.INACTIVE;
import static org.springframework.ide.eclipse.boot.dash.model.RunState.RUNNING;
import static org.springframework.ide.eclipse.boot.dash.model.RunState.STARTING;
import static org.springframework.ide.eclipse.boot.dash.views.sections.BootDashColumn.APP;
import static org.springframework.ide.eclipse.boot.dash.views.sections.BootDashColumn.DEFAULT_PATH;
import static org.springframework.ide.eclipse.boot.dash.views.sections.BootDashColumn.HOST;
import static org.springframework.ide.eclipse.boot.dash.views.sections.BootDashColumn.INSTANCES;
import static org.springframework.ide.eclipse.boot.dash.views.sections.BootDashColumn.PROJECT;
import static org.springframework.ide.eclipse.boot.dash.views.sections.BootDashColumn.RUN_STATE_ICN;
import static org.springframework.ide.eclipse.boot.dash.views.sections.BootDashColumn.TAGS;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;

import org.cloudfoundry.client.lib.CloudCredentials;
import org.cloudfoundry.client.lib.CloudFoundryOperations;
import org.cloudfoundry.client.lib.HttpProxyConfiguration;
import org.cloudfoundry.client.lib.domain.CloudDomain;
import org.cloudfoundry.client.lib.domain.CloudInfo;
import org.cloudfoundry.client.lib.domain.CloudSpace;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.SubMonitor;
import org.eclipse.debug.core.ILaunchConfiguration;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.IType;
import org.eclipse.jdt.core.JavaCore;
import org.osgi.framework.Version;
import org.springframework.ide.eclipse.boot.dash.BootDashActivator;
import org.springframework.ide.eclipse.boot.dash.cloudfoundry.ops.ClientRequests;
import org.springframework.ide.eclipse.boot.dash.model.AbstractRunTarget;
import org.springframework.ide.eclipse.boot.dash.model.BootDashElement;
import org.springframework.ide.eclipse.boot.dash.model.BootDashModel;
import org.springframework.ide.eclipse.boot.dash.model.BootDashModelContext;
import org.springframework.ide.eclipse.boot.dash.model.BootDashViewModel;
import org.springframework.ide.eclipse.boot.dash.model.Connectable.ConnectionStateListener;
import org.springframework.ide.eclipse.boot.dash.model.RunState;
import org.springframework.ide.eclipse.boot.dash.model.RunTargetWithProperties;
import org.springframework.ide.eclipse.boot.dash.model.runtargettypes.RunTargetType;
import org.springframework.ide.eclipse.boot.dash.model.runtargettypes.TargetProperties;
import org.springframework.ide.eclipse.boot.dash.views.sections.BootDashColumn;
import org.springsource.ide.eclipse.commons.cloudfoundry.client.diego.BuildpackSupport;
import org.springsource.ide.eclipse.commons.cloudfoundry.client.diego.BuildpackSupport.Buildpack;
import org.springsource.ide.eclipse.commons.cloudfoundry.client.diego.CloudInfoV2;
import org.springsource.ide.eclipse.commons.cloudfoundry.client.diego.HealthCheckSupport;
import org.springsource.ide.eclipse.commons.cloudfoundry.client.diego.SshClientSupport;
import org.springsource.ide.eclipse.commons.livexp.core.LiveExpression;
import org.springsource.ide.eclipse.commons.livexp.core.LiveVariable;
import org.springsource.ide.eclipse.commons.livexp.core.ValueListener;

public class CloudFoundryRunTarget extends AbstractRunTarget implements RunTargetWithProperties {

	private CloudFoundryTargetProperties targetProperties;

	// Cache these to avoid frequent client calls
	private List<CloudDomain> domains;
	private List<CloudSpace> spaces;
	private List<Buildpack> buildpacks;

	private CloudInfoV2 cachedCloudInfo;
	private LiveVariable<CloudFoundryOperations> cachedClient;
	private CloudFoundryClientFactory clientFactory;

	private List<ConnectionStateListener> connectionStateListeners;

	public CloudFoundryRunTarget(CloudFoundryTargetProperties targetProperties, RunTargetType runTargetType, CloudFoundryClientFactory clientFactory) {
		super(runTargetType, CloudFoundryTargetProperties.getId(targetProperties),
				CloudFoundryTargetProperties.getName(targetProperties));
		this.targetProperties = targetProperties;
		this.clientFactory = clientFactory;
		this.cachedClient = new LiveVariable<>();
		this.connectionStateListeners = new ArrayList<>();
		this.cachedClient.addListener(new ValueListener<CloudFoundryOperations>() {
			@Override
			public void gotValue(LiveExpression<CloudFoundryOperations> exp, CloudFoundryOperations value) {
				for (ConnectionStateListener l : connectionStateListeners) {
					l.changed();
				}
			}
		});
	}

	private static final EnumSet<RunState> RUN_GOAL_STATES = EnumSet.of(INACTIVE, STARTING, RUNNING, DEBUGGING);
	private static final BootDashColumn[] DEFAULT_COLUMNS = { RUN_STATE_ICN, APP, PROJECT, INSTANCES, DEFAULT_PATH, TAGS };
	private static final BootDashColumn[] ALL_COLUMNS = { RUN_STATE_ICN, APP, PROJECT, INSTANCES, HOST, DEFAULT_PATH, TAGS };

	@Override
	public EnumSet<RunState> supportedGoalStates() {
		return RUN_GOAL_STATES;
	}

	@Override
	public List<ILaunchConfiguration> getLaunchConfigs(BootDashElement element) {
		return Collections.emptyList();
	}

	@Override
	public ILaunchConfiguration createLaunchConfig(IJavaProject jp, IType mainType) throws Exception {
		return null;
	}

	public CloudFoundryOperations getClient() {
		return cachedClient.getValue();
	}

	public void connect() throws Exception {
		try {
			this.cachedCloudInfo = null;
			this.domains = null;
			this.spaces = null;
			this.buildpacks = null;
			cachedClient.setValue(createClient());
		} catch (Exception e) {
			cachedClient.setValue(null);
			throw e;
		}
	}

	public void disconnect() {
		this.cachedCloudInfo = null;
		this.domains = null;
		this.spaces = null;
		this.buildpacks = null;
		if (getClient() != null) {
			getClient().logout();
			cachedClient.setValue(null);
		}
	}

	public void addConnectionStateListener(ConnectionStateListener l) {
		connectionStateListeners.add(l);
	}

	public void removeConnectionStateListener(ConnectionStateListener l) {
		connectionStateListeners.remove(l);
	}

	public Version getCCApiVersion() {
		try {
			CloudFoundryOperations client = getClient();
			if (client!=null) {
				CloudInfo info = client.getCloudInfo();
				if (info!=null) {
					String versionString = info.getApiVersion();
					if (versionString!=null) {
						return new Version(versionString);
					}
				}
			}
		} catch (Exception e) {
			BootDashActivator.log(e);
		}
		return null;
	}

	protected CloudFoundryOperations createClient() throws Exception {
		return clientFactory.getClient(this);
	}

	@Override
	public BootDashColumn[] getDefaultColumns() {
		return DEFAULT_COLUMNS;
	}

	@Override
	public BootDashColumn[] getAllColumns() {
		return ALL_COLUMNS;
	}

	@Override
	public BootDashModel createElementsTabelModel(BootDashModelContext context, BootDashViewModel parent) {
		return new CloudFoundryBootDashModel(this, context, parent);
	}

	@Override
	public TargetProperties getTargetProperties() {
		return targetProperties;
	}

	@Override
	public boolean canRemove() {
		return true;
	}

	@Override
	public boolean canDeployAppsTo() {
		return true;
	}

	@Override
	public boolean canDeployAppsFrom() {
		return false;
	}

	@Override
	public void refresh() throws Exception {
		// Fetching a new client always validates the CF credentials
		disconnect();
		connect();
	}

	@Override
	public boolean requiresCredentials() {
		return true;
	}

	public synchronized List<CloudDomain> getDomains(ClientRequests requests, IProgressMonitor monitor)
			throws Exception {
		if (domains == null) {
			SubMonitor subMonitor = SubMonitor.convert(monitor, 10);
			subMonitor.beginTask("Refreshing list of domains for " + getName(), 5);

			domains = requests.getDomains();

			subMonitor.worked(5);
		}
		return domains;
	}

	public synchronized List<CloudSpace> getSpaces(ClientRequests requests, IProgressMonitor monitor) throws Exception {
		if (spaces == null) {
			SubMonitor subMonitor = SubMonitor.convert(monitor, 10);

			subMonitor.beginTask("Refreshing list of spaces for " + getName(), 5);
			spaces = requests.getSpaces();
			subMonitor.worked(5);
		}
		return spaces;
	}

	public boolean isPWS() {
		return "https://api.run.pivotal.io".equals(getUrl());
	}

	private String getUrl() {
		String url = targetProperties.getUrl();
		while (url.endsWith("/")) {
			url = url.substring(0, url.length()-1);
		}
		return url;
	}

	public HealthCheckSupport getHealthCheckSupport() throws Exception {
		CloudFoundryOperations client = getClient();
		HttpProxyConfiguration proxyConf = getProxyConf();
		return new HealthCheckSupport(client, getCloudInfoV2(), targetProperties.isSelfsigned(), proxyConf);
	}

	public HttpProxyConfiguration getProxyConf() {
		//TODO: get this right!!! (But the client in the rest of boot dahs also doesn't do this.
		return null;
	}

	public SshClientSupport getSshClientSupport() throws Exception {
		CloudFoundryOperations client = getClient();
		HttpProxyConfiguration proxyConf = getProxyConf();
		return new SshClientSupport(client, getCloudInfoV2(), targetProperties.isSelfsigned(), proxyConf);
	}

	private CloudInfoV2 getCloudInfoV2() throws Exception {
		//cached cloudInfo as it doesn't really change and is more like a bunch of static info about how a target is configured.
		if (this.cachedCloudInfo==null) {
			CloudFoundryOperations client = getClient();
			CloudCredentials creds = new CloudCredentials(targetProperties.getUsername(), targetProperties.getPassword());
			HttpProxyConfiguration proxyConf = getProxyConf();
			this.cachedCloudInfo = new CloudInfoV2(creds, client.getCloudControllerUrl(), proxyConf, targetProperties.isSelfsigned());
		}
		return this.cachedCloudInfo;
	}

	public String getBuildpack(IProject project) {
		// Only support Java project for now
		IJavaProject javaProject = JavaCore.create(project);

		if (javaProject != null) {
			try {
				if (this.buildpacks == null) {
					CloudCredentials creds = new CloudCredentials(targetProperties.getUsername(),
							targetProperties.getPassword());
					HttpProxyConfiguration proxyConf = getProxyConf();
					BuildpackSupport support = BuildpackSupport.create(getClient(), creds, proxyConf,
							targetProperties.isSelfsigned());

					// Cache it to avoid frequent calls to CF
					this.buildpacks = support.getBuildpacks();
				}

				if (this.buildpacks != null) {
					String javaBuildpack = null;
					// Only chose a java build iff ONE java buildpack exists
					// that contains the java_buildpack pattern.

					for (Buildpack bp : this.buildpacks) {
						// iterate through all buildpacks to make sure only
						// ONE java buildpack exists
						if (bp.getName().contains("java_buildpack")) {
							if (javaBuildpack == null) {
								javaBuildpack = bp.getName();
							} else {
								// If more than two buildpacks contain
								// "java_buildpack", do not chose any. Let CF buildpack
								// detection decided which one to chose.
								javaBuildpack = null;
								break;
							}
						}
					}
					return javaBuildpack;
				}
			} catch (Exception e) {
				BootDashActivator.log(e);
			}
		}

		return null;
	}

}
