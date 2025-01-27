// Copyright 2000-2022 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.codeInsight.daemon.impl.quickfix;

import com.intellij.codeInsight.daemon.QuickFixBundle;
import com.intellij.codeInsight.intention.preview.IntentionPreviewInfo;
import com.intellij.java.JavaBundle;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.project.Project;
import com.intellij.psi.PsiField;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiJavaCodeReferenceElement;
import com.intellij.psi.PsiReferenceExpression;
import org.jetbrains.annotations.NotNull;

import java.util.List;

public class QualifyStaticConstantFix extends StaticImportConstantFix {
  QualifyStaticConstantFix(@NotNull PsiFile file, @NotNull PsiJavaCodeReferenceElement referenceElement) {
    super(file, referenceElement);
  }

  @NotNull
  @Override
  protected String getBaseText() {
    return JavaBundle.message("qualify.static.constant.access");
  }

  @Override
  public @NotNull IntentionPreviewInfo generatePreview(@NotNull Project project, @NotNull Editor editor, @NotNull PsiFile file) {
    return generatePreview(file, (expression, field) -> QualifyStaticMethodCallFix.qualifyStatically(field, project, (PsiReferenceExpression)expression));
  }

  @NotNull
  @Override
  protected StaticImportMethodQuestionAction<PsiField> createQuestionAction(@NotNull List<? extends PsiField> fieldsToImport,
                                                                            @NotNull Project project,
                                                                            Editor editor) {
    return new StaticImportMethodQuestionAction<>(project, editor, fieldsToImport, myRef) {
      @NotNull
      @Override
      protected String getPopupTitle() {
        return QuickFixBundle.message("field.to.import.chooser.title");
      }

      @Override
      protected void doImport(PsiField toImport) {
        PsiJavaCodeReferenceElement element = myRef.getElement();
        if (!(element instanceof PsiReferenceExpression)) return;
        WriteCommandAction.runWriteCommandAction(project, JavaBundle.message("qualify.static.access.command.name"), null, 
                                                 () -> {
                                                   QualifyStaticMethodCallFix.qualifyStatically(toImport, project, (PsiReferenceExpression)element);
                                                 }
        );
      }
    };
  }

  @Override
  protected boolean toAddStaticImports() {
    return false;
  }
}
