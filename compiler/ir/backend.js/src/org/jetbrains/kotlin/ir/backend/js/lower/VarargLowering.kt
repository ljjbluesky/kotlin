/*
 * Copyright 2010-2018 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license
 * that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.ir.backend.js.lower

import org.jetbrains.kotlin.backend.common.FileLoweringPass
import org.jetbrains.kotlin.ir.UNDEFINED_OFFSET
import org.jetbrains.kotlin.ir.backend.js.JsIrBackendContext
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrFile
import org.jetbrains.kotlin.ir.expressions.IrCall
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.expressions.IrSpreadElement
import org.jetbrains.kotlin.ir.expressions.IrVararg
import org.jetbrains.kotlin.ir.expressions.impl.IrCallImpl
import org.jetbrains.kotlin.ir.expressions.impl.IrGetFieldImpl
import org.jetbrains.kotlin.ir.expressions.impl.IrVarargImpl
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.ir.types.classifierOrNull
import org.jetbrains.kotlin.ir.types.impl.IrSimpleTypeImpl
import org.jetbrains.kotlin.ir.util.constructors
import org.jetbrains.kotlin.ir.util.defaultType
import org.jetbrains.kotlin.ir.util.getInlineClassBackingField
import org.jetbrains.kotlin.ir.util.getInlinedClass
import org.jetbrains.kotlin.ir.visitors.IrElementTransformerVoid
import org.jetbrains.kotlin.ir.visitors.transformChildrenVoid

class VarargLowering(val context: JsIrBackendContext) : FileLoweringPass {
    override fun lower(irFile: IrFile) {
        irFile.transformChildrenVoid(VarargTransformer(context))
    }
}

private class VarargTransformer(
    val context: JsIrBackendContext
) : IrElementTransformerVoid() {

    private fun List<IrExpression>.toArrayLiteral(type: IrType, varargElementType: IrType): IrExpression {
        val intrinsic = context.intrinsics.primitiveArrays[type.classifierOrNull]?.let { primitiveType ->
            context.intrinsics.primitiveToLiteralConstructor[primitiveType]
        } ?: context.intrinsics.arrayLiteral

        val startOffset = firstOrNull()?.startOffset ?: UNDEFINED_OFFSET
        val endOffset = lastOrNull()?.endOffset ?: UNDEFINED_OFFSET

        val irVararg = IrVarargImpl(startOffset, endOffset, type, varargElementType, this)

        return IrCallImpl(startOffset, endOffset, type, intrinsic).apply {
            if (intrinsic.owner.typeParameters.isNotEmpty()) putTypeArgument(0, varargElementType)
            putValueArgument(0, irVararg)
        }
    }

    fun IrExpression.unboxInlineClassValue(): IrExpression {
        val inlinedClass = type.getInlinedClass() ?: return this
        val field = getInlineClassBackingField(inlinedClass)
        return IrGetFieldImpl(startOffset, endOffset, field.symbol, inlinedClass.defaultType, this)
    }

    fun IrExpression.boxInlineClassValue(klass: IrClass) =
        IrCallImpl(startOffset, endOffset, klass.defaultType, klass.constructors.single { it.isPrimary }.symbol).also {
            it.putValueArgument(0, this)
        }

    override fun visitVararg(expression: IrVararg): IrExpression {
        expression.transformChildrenVoid(this)

        val currentList = mutableListOf<IrExpression>()
        val segments = mutableListOf<IrExpression>()

        val elementType = expression.varargElementType
        val primitiveElementType: IrType
        val primitiveExpressionType: IrType
        val needBoxing: Boolean
        val arrayInlineClass = expression.type.getInlinedClass()
        if (arrayInlineClass != null) {
            primitiveElementType = getInlineClassBackingField(elementType.getInlinedClass()!!).type
            primitiveExpressionType = getInlineClassBackingField(arrayInlineClass).type
            needBoxing = true
        } else {
            primitiveElementType = elementType
            primitiveExpressionType = expression.type
            needBoxing = false
        }

        for (e in expression.elements) {
            when (e) {
                is IrSpreadElement -> {
                    if (!currentList.isEmpty()) {
                        segments.add(currentList.toArrayLiteral(primitiveExpressionType, primitiveElementType))
                        currentList.clear()
                    }
                    segments.add(if (needBoxing) e.expression.unboxInlineClassValue() else e.expression)
                }

                is IrExpression -> {
                    currentList.add(if (needBoxing) e.unboxInlineClassValue() else e)
                }
            }
        }
        if (!currentList.isEmpty()) {
            segments.add(currentList.toArrayLiteral(primitiveExpressionType, primitiveElementType))
            currentList.clear()
        }

        // empty vararg => empty array literal
        if (segments.isEmpty()) {
            return emptyList().toArrayLiteral(primitiveExpressionType, primitiveElementType)
        }

        // vararg with a single segment => no need to concatenate
        if (segments.size == 1) {
            return if (expression.elements.any { it is IrSpreadElement }) {
                // Single spread operator => need to copy the array
                IrCallImpl(
                    expression.startOffset,
                    expression.endOffset,
                    expression.type,
                    context.intrinsics.jsArraySlice.symbol
                ).apply {
                    putValueArgument(0, segments.first())
                }
            } else {
                val res = segments.first()
                return if (needBoxing)
                    res.boxInlineClassValue(expression.type.getInlinedClass()!!)
                else
                    res
            }
        }

        val arrayLiteral =
            segments.toArrayLiteral(
                IrSimpleTypeImpl(context.intrinsics.array, false, emptyList(), emptyList()),
                context.irBuiltIns.anyType
            )

        val concatFun = if (expression.type.classifierOrNull in context.intrinsics.primitiveArrays.keys) {
            context.intrinsics.primitiveArrayConcat
        } else {
            context.intrinsics.arrayConcat
        }

        var res = IrCallImpl(
            expression.startOffset,
            expression.endOffset,
            expression.type,
            concatFun
        ).apply {
            putValueArgument(0, arrayLiteral)
        }

        return if (needBoxing)
            res.boxInlineClassValue(expression.type.getInlinedClass()!!)
        else
            res
    }

    override fun visitCall(expression: IrCall): IrExpression {
        expression.transformChildrenVoid()
        val size = expression.valueArgumentsCount

        for (i in 0 until size) {
            val argument = expression.getValueArgument(i)
            val parameter = expression.symbol.owner.valueParameters[i]
            if (argument == null && parameter.varargElementType != null) {
                expression.putValueArgument(i, emptyList().toArrayLiteral(parameter.type, parameter.varargElementType!!))
            }
        }

        return expression
    }
}
