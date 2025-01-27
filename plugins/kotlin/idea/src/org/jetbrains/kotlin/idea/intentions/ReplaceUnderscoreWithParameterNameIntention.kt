// Copyright 2000-2021 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.

package org.jetbrains.kotlin.idea.intentions

import com.intellij.openapi.editor.Editor
import org.jetbrains.kotlin.builtins.extractParameterNameFromFunctionTypeArgument
import org.jetbrains.kotlin.descriptors.CallableDescriptor
import org.jetbrains.kotlin.descriptors.ClassDescriptor
import org.jetbrains.kotlin.idea.base.resources.KotlinBundle
import org.jetbrains.kotlin.idea.base.codeInsight.KotlinNameSuggestionProvider
import org.jetbrains.kotlin.idea.core.CollectingNameValidator
import org.jetbrains.kotlin.idea.base.fe10.codeInsight.newDeclaration.Fe10KotlinNameSuggester
import org.jetbrains.kotlin.idea.base.fe10.codeInsight.newDeclaration.Fe10KotlinNewDeclarationNameValidator
import org.jetbrains.kotlin.idea.caches.resolve.analyze
import org.jetbrains.kotlin.idea.caches.resolve.resolveToDescriptorIfAny
import org.jetbrains.kotlin.idea.codeinsight.api.classic.intentions.SelfTargetingOffsetIndependentIntention
import org.jetbrains.kotlin.psi.*
import org.jetbrains.kotlin.psi.psiUtil.getChildrenOfType
import org.jetbrains.kotlin.psi.psiUtil.getParentOfType
import org.jetbrains.kotlin.resolve.BindingContext
import org.jetbrains.kotlin.resolve.DescriptorUtils
import org.jetbrains.kotlin.resolve.calls.util.getParameterForArgument
import org.jetbrains.kotlin.resolve.calls.util.getParentResolvedCall
import org.jetbrains.kotlin.resolve.descriptorUtil.builtIns
import org.jetbrains.kotlin.resolve.lazy.BodyResolveMode

class ReplaceUnderscoreWithParameterNameIntention : SelfTargetingOffsetIndependentIntention<KtCallableDeclaration>(
    KtCallableDeclaration::class.java,
    KotlinBundle.lazyMessage("replace.with.parameter.name")
) {
    override fun isApplicableTo(element: KtCallableDeclaration) =
        element.name == "_" && (element is KtDestructuringDeclarationEntry || element is KtParameter)

    override fun applyTo(element: KtCallableDeclaration, editor: Editor?) {
        val suggestedParameterName = suggestedParameterName(element)
        val validator = CollectingNameValidator(
            filter = Fe10KotlinNewDeclarationNameValidator(element.parent.parent, null, KotlinNameSuggestionProvider.ValidatorTarget.PARAMETER)
        )

        val name = suggestedParameterName?.let {
            Fe10KotlinNameSuggester.suggestNameByName(it, validator)
        } ?: run {
            val elementDescriptor = element.resolveToDescriptorIfAny() as? CallableDescriptor
            elementDescriptor?.returnType?.let { Fe10KotlinNameSuggester.suggestNamesByType(it, validator).firstOrNull() }
        } ?: return

        element.setName(name)
    }

    private fun suggestedParameterName(element: KtCallableDeclaration) = when (element) {
        is KtDestructuringDeclarationEntry -> dataClassParameterName(element)
        is KtParameter -> lambdaParameterName(element)
        else -> null
    }

    private fun lambdaParameterName(element: KtParameter): String? {
        val functionLiteral = element.getParentOfType<KtFunctionLiteral>(strict = true) ?: return null
        val idx = functionLiteral.valueParameters.indexOf(element)
        if (idx == -1) return null
        val context = functionLiteral.analyze(BodyResolveMode.PARTIAL)
        val resolvedCall = element.getParentResolvedCall(context)
        val lambdaArgument = functionLiteral.getParentOfType<KtLambdaArgument>(strict = true) ?: return null
        val lambdaParam = resolvedCall?.getParameterForArgument(lambdaArgument) ?: return null
        return lambdaParam.type.arguments.getOrNull(idx)?.type?.extractParameterNameFromFunctionTypeArgument()?.asString()
    }

    private fun dataClassParameterName(declarationEntry: KtDestructuringDeclarationEntry): String? {
        val context = declarationEntry.analyze()
        val componentResolvedCall = context[BindingContext.COMPONENT_RESOLVED_CALL, declarationEntry] ?: return null
        val receiver = componentResolvedCall.dispatchReceiver ?: componentResolvedCall.extensionReceiver ?: return null
        val classDescriptor = receiver.type.constructor.declarationDescriptor as? ClassDescriptor ?: return null
        return when {
            classDescriptor.isData -> {
                val primaryParameters = classDescriptor.unsubstitutedPrimaryConstructor?.valueParameters
                primaryParameters?.getOrNull(declarationEntry.entryIndex())?.name?.asString()
            }
            DescriptorUtils.isSubclass(classDescriptor, classDescriptor.builtIns.mapEntry) -> {
                listOf("key", "value").getOrNull(declarationEntry.entryIndex())
            }
            else -> null
        }
    }

    private fun KtDestructuringDeclarationEntry.entryIndex() = parent.getChildrenOfType<KtDestructuringDeclarationEntry>().indexOf(this)
}