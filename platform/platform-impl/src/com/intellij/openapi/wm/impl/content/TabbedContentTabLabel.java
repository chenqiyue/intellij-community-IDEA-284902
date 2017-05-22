/*
 * Copyright 2000-2017 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.intellij.openapi.wm.impl.content;

import com.intellij.ide.IdeEventQueue;
import com.intellij.openapi.ui.popup.JBPopup;
import com.intellij.openapi.ui.popup.JBPopupFactory;
import com.intellij.openapi.ui.popup.PopupChooserBuilder;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.util.Pair;
import com.intellij.reference.SoftReference;
import com.intellij.ui.components.JBList;
import com.intellij.ui.content.TabbedContent;
import com.intellij.util.containers.ContainerUtil;
import com.intellij.util.ui.UIUtil;
import org.jetbrains.annotations.NotNull;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.lang.ref.Reference;
import java.lang.ref.WeakReference;
import java.util.List;

/**
 * @author Konstantin Bulenkov
 */
public class TabbedContentTabLabel extends ContentTabLabel {
  private final ComboIcon myComboIcon = new ComboIcon() {
    @Override
    public Rectangle getIconRec() {
      return new Rectangle(getWidth() - getIconWidth() - 3, 0, getIconWidth(), getHeight());
    }

    @Override
    public boolean isActive() {
      return true;
    }
  };
  private final TabbedContent myContent;
  private Reference<JBPopup> myPopupReference = null;

  public TabbedContentTabLabel(@NotNull TabbedContent content, @NotNull TabContentLayout layout) {
    super(content, layout);
    myContent = content;
  }

  @Override
  protected void selectContent() {
    IdeEventQueue.getInstance().getPopupManager().closeAllPopups();

    final List<Pair<String, JComponent>> tabs = myContent.getTabs();
    if (tabs.size() == 1) {
      selectContent(0);
      return;
    }
    else if (tabs.isEmpty()) {
      return;
    }

    final List<String> names = ContainerUtil.map(tabs, tab -> tab.first);
    final JBList<String> list = new JBList<>(names);
    list.installCellRenderer((String name) -> {
      final JLabel label = new JLabel(name);
      label.setBorder(new EmptyBorder(UIUtil.getListCellPadding()));
      return label;
    });

    final PopupChooserBuilder popupBuilder = JBPopupFactory.getInstance().createListPopupBuilder(list);
    final JBPopup popup = popupBuilder.setItemChoosenCallback(() -> {
      int index = list.getSelectedIndex();
      if (index != -1) {
        selectContent(index);
      }
    }).createPopup();
    myPopupReference = new WeakReference<>(popup);
    popup.showUnderneathOf(this);
  }

  private void selectContent(int index) {
    myContent.selectContent(index);
    super.selectContent();
  }

  @Override
  public void update() {
    super.update();
    if (myContent != null) {
      setText(myContent.getTabName());
    }
    if (hasMultipleTabs()) {
      setHorizontalAlignment(LEFT);
    }
  }

  @Override
  public Dimension getPreferredSize() {
    final Dimension size = super.getPreferredSize();
    return hasMultipleTabs() ? new Dimension(size.width + 12, size.height) : size;
  }

  @Override
  protected void paintComponent(Graphics g) {
    super.paintComponent(g);
    if (hasMultipleTabs()) {
      myComboIcon.paintIcon(this, g);
    }
  }

  @Override
  public void removeNotify() {
    super.removeNotify();
    JBPopup popup = SoftReference.dereference(myPopupReference);
    if (popup != null) {
      Disposer.dispose(popup);
      myPopupReference = null;
    }
  }

  @NotNull
  @Override
  public TabbedContent getContent() {
    return myContent;
  }

  private boolean hasMultipleTabs() {
    return myContent != null && myContent.getTabs().size() > 1;
  }
}
