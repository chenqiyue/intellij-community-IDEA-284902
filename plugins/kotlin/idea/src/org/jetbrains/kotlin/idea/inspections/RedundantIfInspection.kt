// Copyright 2000-2021 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.

package org.jetbrains.kotlin.idea.inspections

import com.intellij.codeInsight.CodeInsightUtil
import com.intellij.codeInsight.intention.FileModifier.SafeFieldForPreview
import com.intellij.codeInspection.*
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElementVisitor
import org.jetbrains.kotlin.idea.base.resources.KotlinBundle
import org.jetbrains.kotlin.idea.intentions.negate
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.psi.*

import org.jetbrains.kotlin.idea.codeinsight.api.classic.inspections.AbstractKotlinInspection

class RedundantIfInspection : AbstractKotlinInspection(), CleanupLocalInspectionTool {

    override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean, session: LocalInspectionToolSession): PsiElementVisitor {
        return ifExpressionVisitor { expression ->
            if (expression.condition == null) return@ifExpressionVisitor
            val (redundancyType, branchType) = RedundancyType.of(expression)
            if (redundancyType == RedundancyType.NONE) return@ifExpressionVisitor

            holder.registerProblem(
                expression,
                KotlinBundle.message("redundant.if.statement"),
                ProblemHighlightType.GENERIC_ERROR_OR_WARNING,
                RemoveRedundantIf(redundancyType, branchType)
            )
        }
    }

    private sealed class BranchType {
        object Simple : BranchType()

        object Return : BranchType()

        data class LabeledReturn(val label: String) : BranchType()

        class Assign(val lvalue: KtExpression) : BranchType() {
            override fun equals(other: Any?) = other is Assign && lvalue.text == other.lvalue.text

            override fun hashCode() = lvalue.text.hashCode()
        }
    }

    private enum class RedundancyType {
        NONE,
        THEN_TRUE,
        ELSE_TRUE;

        companion object {
            internal fun of(expression: KtIfExpression): Pair<RedundancyType, BranchType> {
                val (thenReturn, thenType) = expression.then.getBranchExpression() ?: return NONE to BranchType.Simple
                val (elseReturn, elseType) = expression.`else`.getBranchExpression() ?: return NONE to BranchType.Simple

                return when {
                    thenType != elseType -> NONE to BranchType.Simple
                    KtPsiUtil.isTrueConstant(thenReturn) && KtPsiUtil.isFalseConstant(elseReturn) -> THEN_TRUE to thenType
                    KtPsiUtil.isFalseConstant(thenReturn) && KtPsiUtil.isTrueConstant(elseReturn) -> ELSE_TRUE to thenType
                    else -> NONE to BranchType.Simple
                }
            }

            private fun KtExpression?.getBranchExpression(): Pair<KtExpression?, BranchType>? {
                return when (this) {
                    is KtReturnExpression -> {
                        val branchType = labeledExpression?.let { BranchType.LabeledReturn(it.text) } ?: BranchType.Return
                        returnedExpression to branchType
                    }
                    is KtBlockExpression -> statements.singleOrNull()?.getBranchExpression()
                    is KtBinaryExpression -> if (operationToken == KtTokens.EQ && left != null)
                        right to BranchType.Assign(left!!)
                    else
                        null
                    is KtExpression -> this to BranchType.Simple
                    else -> null
                }
            }
        }
    }

    private class RemoveRedundantIf(private val redundancyType: RedundancyType,
                                    @SafeFieldForPreview // may refer to PsiElement of original file but we are only reading from it
                                    private val branchType: BranchType) : LocalQuickFix {
        override fun getName() = KotlinBundle.message("remove.redundant.if.text")
        override fun getFamilyName() = name

        override fun applyFix(project: Project, descriptor: ProblemDescriptor) {
            val element = descriptor.psiElement as KtIfExpression
            val condition = when (redundancyType) {
                RedundancyType.NONE -> return
                RedundancyType.THEN_TRUE -> element.condition!!
                RedundancyType.ELSE_TRUE -> element.condition!!.negate()
            }
            val factory = KtPsiFactory(element)
            element.replace(
                when (branchType) {
                    is BranchType.Return -> factory.createExpressionByPattern("return $0", condition)
                    is BranchType.LabeledReturn -> factory.createExpressionByPattern("return${branchType.label} $0", condition)
                    is BranchType.Assign -> factory.createExpressionByPattern("$0 = $1", branchType.lvalue, condition)
                    else -> condition
                }
            )
        }
    }

}
