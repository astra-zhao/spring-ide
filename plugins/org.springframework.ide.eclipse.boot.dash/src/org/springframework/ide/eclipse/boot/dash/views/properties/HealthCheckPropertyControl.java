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
package org.springframework.ide.eclipse.boot.dash.views.properties;

import org.eclipse.swt.custom.CCombo;
import org.eclipse.swt.events.SelectionAdapter;
import org.eclipse.swt.events.SelectionEvent;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.ui.views.properties.tabbed.TabbedPropertySheetPage;
import org.springframework.ide.eclipse.boot.dash.cloudfoundry.CloudDashElement;
import org.springframework.ide.eclipse.boot.dash.cloudfoundry.ops.SetHealthCheckOperation;
import org.springframework.ide.eclipse.boot.dash.model.BootDashElement;
import org.springframework.ide.eclipse.boot.util.StringUtil;
import org.springsource.ide.eclipse.commons.cloudfoundry.client.diego.HealthCheckSupport;

/**
 * @author Kris De Volder
 */
public class HealthCheckPropertyControl extends AbstractBdePropertyControl {

	private CloudDashElement app;
	private CCombo selection;

	@Override
	public void createControl(Composite composite, TabbedPropertySheetPage page) {
		super.createControl(composite, page);
		page.getWidgetFactory().createLabel(composite, "Healthcheck:");
		selection = page.getWidgetFactory().createCCombo(composite);
		selection.setItems(HealthCheckSupport.HC_ALL);
		refreshControl();
		selection.addSelectionListener(new SelectionAdapter() {
			@Override
			public void widgetSelected(SelectionEvent e) {
				handle();
			}

			@Override
			public void widgetDefaultSelected(SelectionEvent e) {
				handle();
			}

			private void handle() {
				String selected = selection.getText();
				if (StringUtil.hasText(selected)) {
					SetHealthCheckOperation op = new SetHealthCheckOperation(app, selected);
					app.getCloudModel().getOperationsExecution().runOpAsynch(op);
				}
			}
		});
	}

	@Override
	public void refreshControl() {
		if (app!=null && selection!=null && !selection.isDisposed()) {
			String hc = app.getHealthCheck();
			if (StringUtil.hasText(hc)) {
				selection.setText(hc);
			} else {
				selection.setText("");
			}
		}
	}

	@Override
	public void setInput(BootDashElement bde) {
		super.setInput(bde);
		app = (CloudDashElement)bde;
	}

}