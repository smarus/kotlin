/*
 * Copyright 2010-2019 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license
 * that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.fir.backend

import com.intellij.psi.PsiFile
import org.jetbrains.kotlin.backend.common.descriptors.*
import org.jetbrains.kotlin.descriptors.*
import org.jetbrains.kotlin.fir.*
import org.jetbrains.kotlin.fir.declarations.*
import org.jetbrains.kotlin.fir.declarations.impl.*
import org.jetbrains.kotlin.fir.descriptors.FirModuleDescriptor
import org.jetbrains.kotlin.fir.expressions.*
import org.jetbrains.kotlin.fir.expressions.impl.FirElseIfTrueCondition
import org.jetbrains.kotlin.fir.expressions.impl.FirUnitExpression
import org.jetbrains.kotlin.fir.expressions.impl.FirWhenSubjectExpression
import org.jetbrains.kotlin.fir.references.FirPropertyFromParameterCallableReference
import org.jetbrains.kotlin.fir.resolve.FirSymbolProvider
import org.jetbrains.kotlin.fir.resolve.buildUseSiteScope
import org.jetbrains.kotlin.fir.resolve.getCallableSymbols
import org.jetbrains.kotlin.fir.resolve.toSymbol
import org.jetbrains.kotlin.fir.scopes.ProcessorAction
import org.jetbrains.kotlin.fir.symbols.CallableId
import org.jetbrains.kotlin.fir.symbols.ConeClassLikeLookupTagImpl
import org.jetbrains.kotlin.fir.symbols.impl.FirClassSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirFunctionSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirTypeAliasSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirVariableSymbol
import org.jetbrains.kotlin.fir.types.ConeClassLikeType
import org.jetbrains.kotlin.fir.types.FirResolvedTypeRef
import org.jetbrains.kotlin.fir.types.FirTypeRef
import org.jetbrains.kotlin.fir.types.impl.ConeClassTypeImpl
import org.jetbrains.kotlin.fir.types.impl.FirResolvedTypeRefImpl
import org.jetbrains.kotlin.fir.visitors.FirVisitor
import org.jetbrains.kotlin.ir.IrElement
import org.jetbrains.kotlin.ir.IrStatement
import org.jetbrains.kotlin.ir.declarations.*
import org.jetbrains.kotlin.ir.declarations.impl.*
import org.jetbrains.kotlin.ir.descriptors.IrBuiltIns
import org.jetbrains.kotlin.ir.expressions.*
import org.jetbrains.kotlin.ir.expressions.impl.*
import org.jetbrains.kotlin.ir.symbols.*
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.ir.types.classifierOrFail
import org.jetbrains.kotlin.ir.types.classifierOrNull
import org.jetbrains.kotlin.ir.types.impl.IrErrorTypeImpl
import org.jetbrains.kotlin.ir.types.makeNullable
import org.jetbrains.kotlin.ir.util.*
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.psi.*
import org.jetbrains.kotlin.psi2ir.PsiSourceManager
import org.jetbrains.kotlin.types.Variance

class Fir2IrVisitor(
    private val session: FirSession,
    private val moduleDescriptor: FirModuleDescriptor,
    private val symbolTable: SymbolTable,
    private val irBuiltIns: IrBuiltIns
) : FirVisitor<IrElement, Any?>() {
    companion object {
        private val KOTLIN = FqName("kotlin")
    }

    private val typeContext = session.typeContext

    private val declarationStorage = Fir2IrDeclarationStorage(session, symbolTable, moduleDescriptor)

    private fun FqName.simpleType(name: String): IrType =
        FirResolvedTypeRefImpl(
            session, null,
            ConeClassTypeImpl(
                ConeClassLikeLookupTagImpl(
                    ClassId(this, Name.identifier(name))
                ),
                typeArguments = emptyArray(),
                isNullable = false
            ),
            isMarkedNullable = false,
            annotations = emptyList()
        ).toIrType(session, declarationStorage)

    private val nothingType = KOTLIN.simpleType("Nothing")

    private val unitType = KOTLIN.simpleType("Unit")

    private val booleanType = KOTLIN.simpleType("Boolean")

    private fun ModuleDescriptor.findPackageFragmentForFile(file: FirFile): PackageFragmentDescriptor =
        getPackage(file.packageFqName).fragments.first()

    private val parentStack = mutableListOf<IrDeclarationParent>()

    private fun <T : IrDeclarationParent> T.withParent(f: T.() -> Unit): T {
        parentStack += this
        f()
        parentStack.removeAt(parentStack.size - 1)
        return this
    }

    private fun <T : IrDeclaration> T.setParentByParentStack(): T {
        this.parent = parentStack.last()
        return this
    }

    private val functionStack = mutableListOf<IrSimpleFunction>()

    private fun <T : IrSimpleFunction> T.withFunction(f: T.() -> Unit): T {
        functionStack += this
        f()
        functionStack.removeAt(functionStack.size - 1)
        return this
    }

    private val propertyStack = mutableListOf<IrProperty>()

    private fun IrProperty.withProperty(f: IrProperty.() -> Unit): IrProperty {
        propertyStack += this
        f()
        propertyStack.removeAt(propertyStack.size - 1)
        return this
    }

    private val classStack = mutableListOf<IrClass>()

    private fun IrClass.withClass(f: IrClass.() -> Unit): IrClass {
        classStack += this
        f()
        classStack.removeAt(classStack.size - 1)
        return this
    }

    private val subjectVariableStack = mutableListOf<IrVariable>()

    private fun <T> IrVariable?.withSubject(f: () -> T): T {
        if (this != null) subjectVariableStack += this
        val result = f()
        if (this != null) subjectVariableStack.removeAt(subjectVariableStack.size - 1)
        return result
    }

    override fun visitElement(element: FirElement, data: Any?): IrElement {
        TODO("Should not be here")
    }

    override fun visitFile(file: FirFile, data: Any?): IrFile {
        return IrFileImpl(
            PsiSourceManager.PsiFileEntry(file.psi as PsiFile),
            moduleDescriptor.findPackageFragmentForFile(file)
        ).withParent {
            file.declarations.forEach {
                val irDeclaration = it.toIrDeclaration() ?: return@forEach
                declarations += irDeclaration
            }

            file.annotations.forEach {
                annotations += it.accept(this@Fir2IrVisitor, data) as IrCall
            }
        }
    }

    private fun FirDeclaration.toIrDeclaration(): IrDeclaration? {
        if (this is FirTypeAlias) return null
        return accept(this@Fir2IrVisitor, null) as IrDeclaration
    }

    private fun FirTypeRef.collectFunctionNamesFromThisAndSupertypes(result: MutableList<Name> = mutableListOf()): List<Name> {
        if (this is FirResolvedTypeRef) {
            val superType = type
            if (superType is ConeClassLikeType) {
                when (val superSymbol = superType.lookupTag.toSymbol(this@Fir2IrVisitor.session)) {
                    is FirClassSymbol -> {
                        val superClass = superSymbol.fir
                        for (declaration in superClass.declarations) {
                            if (declaration is FirNamedFunction) {
                                result += declaration.name
                            }
                        }
                        superClass.collectFunctionNamesFromSupertypes(result)
                    }
                    is FirTypeAliasSymbol -> {
                        val superAlias = superSymbol.fir
                        superAlias.expandedTypeRef.collectFunctionNamesFromThisAndSupertypes(result)
                    }
                }
            }
        }
        return result
    }

    private fun FirClass.collectFunctionNamesFromSupertypes(result: MutableList<Name> = mutableListOf()): List<Name> {
        for (superTypeRef in superTypeRefs) {
            superTypeRef.collectFunctionNamesFromThisAndSupertypes(result)
        }
        return result
    }

    private fun FirClass.getPrimaryConstructorIfAny(): FirConstructor? =
        (declarations.firstOrNull() as? FirConstructor)?.takeIf { it.isPrimary }

    private fun IrClass.setClassContent(klass: FirClass) {
        for (superTypeRef in klass.superTypeRefs) {
            superTypes += superTypeRef.toIrType(session, declarationStorage)
        }
        if (klass is FirRegularClass) {
            for ((index, typeParameter) in klass.typeParameters.withIndex()) {
                typeParameters += declarationStorage.getIrTypeParameter(typeParameter, index).setParentByParentStack()
            }
        }
        val useSiteScope = (klass as? FirRegularClass)?.buildUseSiteScope(session)
        val superTypesFunctionNames = klass.collectFunctionNamesFromSupertypes()
        declarationStorage.enterScope(descriptor)
        val primaryConstructor = klass.getPrimaryConstructorIfAny()
        val irPrimaryConstructor = primaryConstructor?.accept(this@Fir2IrVisitor, null) as IrConstructor?
        withClass {
            if (irPrimaryConstructor != null) {
                declarations += irPrimaryConstructor
            }
            val processedFunctionNames = mutableListOf<Name>()
            klass.declarations.forEach {
                if (it !is FirConstructor || !it.isPrimary) {
                    val irDeclaration = it.toIrDeclaration() ?: return@forEach
                    declarations += irDeclaration
                    if (it is FirNamedFunction) {
                        processedFunctionNames += it.name
                    }
                }
            }
            for (name in superTypesFunctionNames) {
                if (name in processedFunctionNames) continue
                processedFunctionNames += name
                useSiteScope?.processFunctionsByName(name) { functionSymbol ->
                    if (functionSymbol is FirFunctionSymbol) {
                        val originalFunction = functionSymbol.fir as FirNamedFunction

                        val fakeOverrideSymbol = FirFunctionSymbol(functionSymbol.callableId, true)
                        val fakeOverrideFunction = with(originalFunction) {
                            // TODO: consider using here some light-weight functions instead of pseudo-real FirMemberFunctionImpl
                            // As second alternative, we can invent some light-weight kind of FirRegularClass
                            FirMemberFunctionImpl(
                                this@Fir2IrVisitor.session, psi, fakeOverrideSymbol,
                                name, receiverTypeRef, returnTypeRef
                            ).apply {
                                status = originalFunction.status as FirDeclarationStatusImpl
                                valueParameters += originalFunction.valueParameters.map { valueParameter ->
                                    with(valueParameter) {
                                        FirValueParameterImpl(
                                            this@Fir2IrVisitor.session, psi,
                                            this.name, this.returnTypeRef,
                                            defaultValue, isCrossinline, isNoinline, isVararg,
                                            FirVariableSymbol(valueParameter.symbol.callableId)
                                        )
                                    }
                                }
                            }
                        }

                        val irFunction = declarationStorage.getIrFunction(
                            fakeOverrideFunction, setParent = false, origin = IrDeclarationOrigin.FAKE_OVERRIDE
                        )
                        declarations += irFunction.setParentByParentStack().withFunction {
                            setFunctionContent(irFunction.descriptor, fakeOverrideFunction, firOverriddenSymbol = functionSymbol)
                        }

                    }
                    ProcessorAction.STOP
                }
            }
            klass.annotations.forEach {
                annotations += it.accept(this@Fir2IrVisitor, null) as IrCall
            }
        }
        if (irPrimaryConstructor != null) {
            declarationStorage.leaveScope(irPrimaryConstructor.descriptor)
        }
        declarationStorage.leaveScope(descriptor)
    }

    override fun visitRegularClass(regularClass: FirRegularClass, data: Any?): IrElement {
        return declarationStorage.getIrClass(regularClass, setParent = false)
            .setParentByParentStack()
            .withParent {
                setClassContent(regularClass)
            }
    }

    private fun <T : IrFunction> T.setFunctionContent(
        descriptor: FunctionDescriptor,
        firFunction: FirFunction,
        firOverriddenSymbol: FirFunctionSymbol? = null
    ): T {
        setParentByParentStack()
        withParent {
            if (firFunction is FirNamedFunction) {
                for ((index, typeParameter) in firFunction.typeParameters.withIndex()) {
                    typeParameters += declarationStorage.getIrTypeParameter(typeParameter, index).setParentByParentStack()
                }
            }
            val firFunctionSymbol = (firFunction as? FirNamedFunction)?.symbol
            val lastClass = classStack.lastOrNull()
            val containingClass = if (firOverriddenSymbol == null || firFunctionSymbol == null) {
                lastClass
            } else {
                val callableId = firFunctionSymbol.callableId
                val ownerClassId = callableId.classId
                if (ownerClassId == null) {
                    lastClass
                } else {
                    val classLikeSymbol = session.service<FirSymbolProvider>().getClassLikeSymbolByFqName(ownerClassId)
                    if (classLikeSymbol !is FirClassSymbol) {
                        lastClass
                    } else {
                        val firClass = classLikeSymbol.fir
                        declarationStorage.getIrClass(firClass, setParent = false)
                    }
                }
            }
            if (firFunction !is FirConstructor && containingClass != null) {
                val thisOrigin = IrDeclarationOrigin.DEFINED
                val thisType = containingClass.thisReceiver!!.type
                dispatchReceiverParameter = symbolTable.declareValueParameter(
                    startOffset, endOffset, thisOrigin, WrappedValueParameterDescriptor(),
                    thisType
                ) { symbol ->
                    IrValueParameterImpl(
                        startOffset, endOffset, thisOrigin, symbol,
                        Name.special("<this>"), -1, thisType,
                        varargElementType = null, isCrossinline = false, isNoinline = false
                    ).setParentByParentStack()
                }
            }
            for ((valueParameter, firValueParameter) in valueParameters.zip(firFunction.valueParameters)) {
                valueParameter.setDefaultValue(firValueParameter)
            }
            if (firOverriddenSymbol != null && this is IrSimpleFunction && firFunctionSymbol != null) {
                val overriddenSymbol = declarationStorage.getIrFunctionSymbol(firOverriddenSymbol)
                if (overriddenSymbol is IrSimpleFunctionSymbol) {
                    overriddenSymbols += overriddenSymbol
                }
            }
            body = firFunction.body?.convertToIrBlockBody()
            if (this !is IrConstructor || !this.isPrimary) {
                // Scope for primary constructor should be left after class declaration
                declarationStorage.leaveScope(descriptor)
            }
        }
        return this
    }

    override fun visitConstructor(constructor: FirConstructor, data: Any?): IrElement {
        val irConstructor = declarationStorage.getIrConstructor(constructor, setParent = false)
        return irConstructor.setParentByParentStack().setFunctionContent(irConstructor.descriptor, constructor).apply {
            if (!parentAsClass.isAnnotationClass) {
                val body = this.body as IrBlockBody? ?: IrBlockBodyImpl(startOffset, endOffset)
                val delegatedConstructor = constructor.delegatedConstructor
                if (delegatedConstructor != null) {
                    body.statements += delegatedConstructor.accept(this@Fir2IrVisitor, null) as IrStatement
                }
                if (delegatedConstructor?.isThis == false) {
                    val irClass = parent as IrClass
                    body.statements += IrInstanceInitializerCallImpl(
                        startOffset, endOffset, irClass.symbol, constructedClassType
                    )
                }
                if (body.statements.isNotEmpty()) {
                    this.body = body
                }
            }
        }
    }

    override fun visitAnonymousInitializer(anonymousInitializer: FirAnonymousInitializer, data: Any?): IrElement {
        val origin = IrDeclarationOrigin.DEFINED
        val parent = parentStack.last() as IrClass
        return anonymousInitializer.convertWithOffsets { startOffset, endOffset ->
            symbolTable.declareAnonymousInitializer(
                startOffset, endOffset, origin, parent.descriptor
            ).apply {
                declarationStorage.enterScope(descriptor)
                body = anonymousInitializer.body!!.convertToIrBlockBody()
                declarationStorage.leaveScope(descriptor)
            }
        }
    }

    override fun visitDelegatedConstructorCall(delegatedConstructorCall: FirDelegatedConstructorCall, data: Any?): IrElement {
        val constructedTypeRef = delegatedConstructorCall.constructedTypeRef
        val constructedClassSymbol = with(typeContext) {
            (constructedTypeRef as FirResolvedTypeRef).type.typeConstructor()
        } as FirClassSymbol
        val constructedIrType = constructedTypeRef.toIrType(session, declarationStorage)
        // TODO: find delegated constructor correctly
        val classId = constructedClassSymbol.classId
        val constructorId = CallableId(classId.packageFqName, classId.relativeClassName, classId.shortClassName)
        val constructorSymbol = session.service<FirSymbolProvider>().getCallableSymbols(constructorId).first {
            delegatedConstructorCall.arguments.size <= ((it as FirFunctionSymbol).fir as FirFunction).valueParameters.size
        }
        return delegatedConstructorCall.convertWithOffsets { startOffset, endOffset ->
            IrDelegatingConstructorCallImpl(
                startOffset, endOffset,
                constructedIrType,
                declarationStorage.getIrFunctionSymbol(constructorSymbol as FirFunctionSymbol) as IrConstructorSymbol
            ).apply {
                for ((index, argument) in delegatedConstructorCall.arguments.withIndex()) {
                    val argumentExpression = argument.accept(this@Fir2IrVisitor, data) as IrExpression
                    putValueArgument(index, argumentExpression)
                }
            }
        }
    }

    override fun visitNamedFunction(namedFunction: FirNamedFunction, data: Any?): IrElement {
        val irFunction = declarationStorage.getIrFunction(namedFunction, setParent = false)
        return irFunction.setParentByParentStack().withFunction {
            setFunctionContent(irFunction.descriptor, namedFunction)
        }
    }

    override fun visitAnonymousFunction(anonymousFunction: FirAnonymousFunction, data: Any?): IrElement {
        val irFunction = declarationStorage.getIrLocalFunction(anonymousFunction)
        irFunction.setParentByParentStack().withFunction {
            setFunctionContent(irFunction.descriptor, anonymousFunction)
        }
        return anonymousFunction.convertWithOffsets { startOffset, endOffset ->
            val type = anonymousFunction.typeRef.toIrType(session, declarationStorage)
            val origin = when (anonymousFunction.psi) {
                is KtFunctionLiteral -> IrStatementOrigin.LAMBDA
                else -> IrStatementOrigin.ANONYMOUS_FUNCTION
            }
            IrBlockImpl(
                startOffset, endOffset, type, origin,
                listOf(
                    irFunction, IrFunctionReferenceImpl(
                        startOffset, endOffset, type, irFunction.symbol, irFunction.descriptor, 0, origin
                    )
                )
            )
        }
    }

    private fun IrValueParameter.setDefaultValue(firValueParameter: FirValueParameter) {
        val firDefaultValue = firValueParameter.defaultValue
        if (firDefaultValue != null) {
            this.defaultValue = IrExpressionBodyImpl(
                firDefaultValue.accept(this@Fir2IrVisitor, null) as IrExpression
            )
        }
    }

    override fun visitVariable(variable: FirVariable, data: Any?): IrElement {
        val irVariable = declarationStorage.createAndSaveIrVariable(variable)
        return irVariable.setParentByParentStack().apply {
            val initializer = variable.initializer
            if (initializer != null) {
                this.initializer = initializer.accept(this@Fir2IrVisitor, data) as IrExpression
            }
        }
    }

    private fun IrProperty.setPropertyContent(descriptor: PropertyDescriptor, property: FirProperty): IrProperty {
        val initializer = property.initializer
        val irParent = this.parent
        val type = property.returnTypeRef.toIrType(session, declarationStorage)
        // TODO: this checks are very preliminary, FIR resolve should determine backing field presence itself
        if (property.modality != Modality.ABSTRACT && (irParent !is IrClass || !irParent.isInterface)) {
            if (initializer != null || property.getter is FirDefaultPropertyGetter ||
                property.isVar && property.setter is FirDefaultPropertySetter
            ) {
                val backingOrigin = IrDeclarationOrigin.PROPERTY_BACKING_FIELD
                backingField = symbolTable.declareField(
                    startOffset, endOffset, backingOrigin, descriptor, type
                ) { symbol ->
                    IrFieldImpl(
                        startOffset, endOffset, backingOrigin, symbol,
                        property.name, type, property.visibility,
                        isFinal = property.isVal, isExternal = false, isStatic = property.isStatic
                    )
                }.setParentByParentStack().withParent {
                    val initializerExpression = initializer?.accept(this@Fir2IrVisitor, null) as IrExpression?
                    this.initializer = initializerExpression?.let { IrExpressionBodyImpl(it) }
                }
            }
        }
        getter = property.getter.accept(this@Fir2IrVisitor, type) as IrSimpleFunction
        if (property.isVar) {
            setter = property.setter.accept(this@Fir2IrVisitor, type) as IrSimpleFunction
        }
        property.annotations.forEach {
            annotations += it.accept(this@Fir2IrVisitor, null) as IrCall
        }
        return this
    }

    override fun visitProperty(property: FirProperty, data: Any?): IrProperty {
        val irProperty = declarationStorage.getIrProperty(property, setParent = false)
        return irProperty.setParentByParentStack().withProperty { setPropertyContent(irProperty.descriptor, property) }
    }

    private fun IrFieldAccessExpression.setReceiver(declaration: IrDeclaration): IrFieldAccessExpression {
        if (declaration is IrFunction) {
            val dispatchReceiver = declaration.dispatchReceiverParameter
            if (dispatchReceiver != null) {
                receiver = IrGetValueImpl(startOffset, endOffset, dispatchReceiver.symbol)
            }
        }
        return this
    }

    private fun <T : IrFunction> T.declareParameters(function: FirFunction) {
        return with(declarationStorage) {
            declareParameters(function)
        }
    }


    private fun createPropertyAccessor(
        propertyAccessor: FirPropertyAccessor, startOffset: Int, endOffset: Int,
        correspondingProperty: IrProperty, isDefault: Boolean, propertyType: IrType
    ): IrSimpleFunction {
        val origin = when {
            isDefault -> IrDeclarationOrigin.DEFAULT_PROPERTY_ACCESSOR
            else -> IrDeclarationOrigin.DEFINED
        }
        val isSetter = propertyAccessor.isSetter
        val prefix = if (isSetter) "set" else "get"
        val descriptor = WrappedSimpleFunctionDescriptor()
        return symbolTable.declareSimpleFunction(
            startOffset, endOffset, origin, descriptor
        ) { symbol ->
            val accessorReturnType = propertyAccessor.returnTypeRef.toIrType(session, declarationStorage)
            IrFunctionImpl(
                startOffset, endOffset, origin, symbol,
                Name.special("<$prefix-${correspondingProperty.name}>"),
                propertyAccessor.visibility, correspondingProperty.modality, accessorReturnType,
                isInline = false, isExternal = false, isTailrec = false, isSuspend = false
            ).withFunction {
                descriptor.bind(this)
                declarationStorage.enterScope(descriptor)
                if (!isDefault) {
                    declareParameters(propertyAccessor)
                }
                setFunctionContent(descriptor, propertyAccessor).apply {
                    correspondingPropertySymbol = symbolTable.referenceProperty(correspondingProperty.descriptor)
                    if (isDefault) {
                        withParent {
                            declarationStorage.enterScope(descriptor)
                            val backingField = correspondingProperty.backingField
                            if (isSetter) {
                                valueParameters += symbolTable.declareValueParameter(
                                    startOffset, endOffset, origin, WrappedValueParameterDescriptor(), propertyType
                                ) { symbol ->
                                    IrValueParameterImpl(
                                        startOffset, endOffset, IrDeclarationOrigin.DEFINED, symbol,
                                        Name.special("<set-?>"), 0, propertyType,
                                        varargElementType = null,
                                        isCrossinline = false, isNoinline = false
                                    ).setParentByParentStack()
                                }
                            }
                            val fieldSymbol = symbolTable.referenceField(correspondingProperty.descriptor)
                            val declaration = this
                            if (backingField != null) {
                                body = IrBlockBodyImpl(
                                    startOffset, endOffset,
                                    listOf(
                                        if (isSetter) {
                                            IrSetFieldImpl(startOffset, endOffset, fieldSymbol, accessorReturnType).apply {
                                                setReceiver(declaration)
                                                value = IrGetValueImpl(startOffset, endOffset, propertyType, valueParameters.first().symbol)
                                            }
                                        } else {
                                            IrReturnImpl(
                                                startOffset, endOffset, nothingType, symbol,
                                                IrGetFieldImpl(startOffset, endOffset, fieldSymbol, propertyType).setReceiver(declaration)
                                            )
                                        }
                                    )
                                )
                            }
                            declarationStorage.leaveScope(descriptor)
                        }
                    }
                }
            }
        }
    }

    override fun visitPropertyAccessor(propertyAccessor: FirPropertyAccessor, data: Any?): IrElement {
        val correspondingProperty = propertyStack.last()
        return propertyAccessor.convertWithOffsets { startOffset, endOffset ->
            createPropertyAccessor(
                propertyAccessor, startOffset, endOffset, correspondingProperty,
                isDefault = propertyAccessor is FirDefaultPropertyGetter || propertyAccessor is FirDefaultPropertySetter,
                propertyType = data as IrType
            )
        }
    }

    override fun visitReturnExpression(returnExpression: FirReturnExpression, data: Any?): IrElement {
        val firTarget = returnExpression.target.labeledElement
        var irTarget = functionStack.last()
        for (potentialTarget in functionStack.asReversed()) {
            // TODO: remove comparison by name
            if (potentialTarget.name == (firTarget as? FirNamedFunction)?.name) {
                irTarget = potentialTarget
                break
            }
        }
        return returnExpression.convertWithOffsets { startOffset, endOffset ->
            val result = returnExpression.result
            IrReturnImpl(
                startOffset, endOffset, nothingType,
                symbolTable.referenceSimpleFunction(irTarget.descriptor),
                if (result is FirBlock) result.convertToIrExpressionOrBlock() else result.accept(this, data) as IrExpression
            )
        }
    }

    override fun visitExpression(expression: FirExpression, data: Any?): IrElement {
        if (expression is FirUnitExpression) {
            return expression.convertWithOffsets { startOffset, endOffset ->
                IrGetObjectValueImpl(
                    startOffset, endOffset, unitType,
                    symbolTable.referenceClass(irBuiltIns.builtIns.unit)
                )
            }
        }
        return super.visitExpression(expression, data)
    }

    override fun visitNamedArgumentExpression(namedArgumentExpression: FirNamedArgumentExpression, data: Any?): IrElement {
        // TODO: change this temporary hack to something correct
        return namedArgumentExpression.expression.toIrExpression()
    }

    private fun FirQualifiedAccess.toIrExpression(typeRef: FirTypeRef): IrExpression {
        val type = typeRef.toIrType(this@Fir2IrVisitor.session, declarationStorage)
        val symbol = calleeReference.toSymbol(declarationStorage)
        return typeRef.convertWithOffsets { startOffset, endOffset ->
            when {
                symbol is IrFunctionSymbol -> IrCallImpl(startOffset, endOffset, type, symbol)
                symbol is IrPropertySymbol && symbol.isBound -> {
                    val getter = symbol.owner.getter
                    if (getter != null) {
                        IrCallImpl(startOffset, endOffset, type, getter.symbol)
                    } else {
                        IrErrorCallExpressionImpl(startOffset, endOffset, type, "No getter found for ${calleeReference.render()}")
                    }
                }
                symbol is IrValueSymbol -> IrGetValueImpl(
                    startOffset, endOffset, type, symbol,
                    if (calleeReference is FirPropertyFromParameterCallableReference) {
                        IrStatementOrigin.INITIALIZE_PROPERTY_FROM_PARAMETER
                    } else null
                )
                else -> IrErrorCallExpressionImpl(startOffset, endOffset, type, "Unresolved reference: ${calleeReference.render()}")
            }
        }
    }

    private fun FirAnnotationCall.toIrExpression(): IrExpression {
        val type = (annotationTypeRef as? FirResolvedTypeRef)?.type?.toIrType(this@Fir2IrVisitor.session, declarationStorage)
        val symbol = type?.classifierOrNull
        return convertWithOffsets { startOffset, endOffset ->
            when (symbol) {
                is IrClassSymbol -> {
                    val irClass = symbol.owner
                    IrCallImpl(startOffset, endOffset, type, irClass.constructors.first().symbol)
                }
                else -> IrErrorCallExpressionImpl(startOffset, endOffset, type ?: createErrorType(), "Unresolved reference: ${render()}")
            }
        }
    }

    private fun IrExpression.applyCallArguments(call: FirCall): IrExpression {
        return when (this) {
            is IrCallImpl -> apply {
                for ((index, argument) in call.arguments.withIndex()) {
                    val argumentExpression = argument.accept(this@Fir2IrVisitor, null) as IrExpression
                    putValueArgument(index, argumentExpression)
                }
            }
            is IrErrorCallExpressionImpl -> apply {
                for (argument in call.arguments) {
                    addArgument(argument.accept(this@Fir2IrVisitor, null) as IrExpression)
                }
            }
            else -> this
        }
    }

    override fun visitFunctionCall(functionCall: FirFunctionCall, data: Any?): IrElement {
        return functionCall.toIrExpression(functionCall.typeRef).applyCallArguments(functionCall)
    }

    override fun visitAnnotationCall(annotationCall: FirAnnotationCall, data: Any?): IrElement {
        return annotationCall.toIrExpression().applyCallArguments(annotationCall)
    }

    override fun visitQualifiedAccessExpression(qualifiedAccessExpression: FirQualifiedAccessExpression, data: Any?): IrElement {
        return qualifiedAccessExpression.toIrExpression(qualifiedAccessExpression.typeRef)
    }

    private fun generateErrorCallExpression(startOffset: Int, endOffset: Int, calleeReference: FirReference): IrErrorCallExpression {
        return IrErrorCallExpressionImpl(
            startOffset, endOffset, IrErrorTypeImpl(null, emptyList(), Variance.INVARIANT),
            "Unresolved reference: ${calleeReference.render()}"
        )
    }

    override fun visitVariableAssignment(variableAssignment: FirVariableAssignment, data: Any?): IrElement {
        val calleeReference = variableAssignment.calleeReference
        val symbol = calleeReference.toSymbol(declarationStorage)
        return variableAssignment.convertWithOffsets { startOffset, endOffset ->
            if (symbol != null && symbol.isBound) {
                when (symbol) {
                    is IrFieldSymbol -> IrSetFieldImpl(
                        startOffset, endOffset, symbol, symbol.owner.type
                    ).apply {
                        value = variableAssignment.rValue.accept(this@Fir2IrVisitor, data) as IrExpression
                    }
                    is IrPropertySymbol -> {
                        val irProperty = symbol.owner
                        val backingField = irProperty.backingField
                        if (backingField != null) {
                            IrSetFieldImpl(
                                startOffset, endOffset, backingField.symbol, backingField.symbol.owner.type
                            ).apply {
                                value = variableAssignment.rValue.accept(this@Fir2IrVisitor, data) as IrExpression
                            }
                        } else {
                            generateErrorCallExpression(startOffset, endOffset, calleeReference)
                        }
                    }
                    else -> generateErrorCallExpression(startOffset, endOffset, calleeReference)
                }
            } else {
                generateErrorCallExpression(startOffset, endOffset, calleeReference)
            }
        }
    }

    override fun <T> visitConstExpression(constExpression: FirConstExpression<T>, data: Any?): IrElement {
        return constExpression.convertWithOffsets { startOffset, endOffset ->
            IrConstImpl(
                startOffset, endOffset,
                constExpression.typeRef.toIrType(session, declarationStorage),
                constExpression.kind, constExpression.value
            )
        }
    }

    override fun visitAnonymousObject(anonymousObject: FirAnonymousObject, data: Any?): IrElement {
        val anonymousClass = declarationStorage.getIrAnonymousObject(anonymousObject).setParentByParentStack().withParent {
            setClassContent(anonymousObject)
        }
        val anonymousClassType = anonymousClass.thisReceiver!!.type
        return anonymousObject.convertWithOffsets { startOffset, endOffset ->
            IrBlockImpl(
                startOffset, endOffset, anonymousClassType, IrStatementOrigin.OBJECT_LITERAL,
                listOf(
                    anonymousClass,
                    IrCallImpl(startOffset, endOffset, anonymousClassType, anonymousClass.constructors.first().symbol)
                )
            )
        }
    }

    // ==================================================================================

    private fun FirStatement.toIrStatement(): IrStatement? {
        if (this is FirTypeAlias) return null
        return accept(this@Fir2IrVisitor, null) as IrStatement
    }

    private fun FirExpression.toIrExpression(): IrExpression {
        return when {
            this is FirBlock -> convertToIrExpressionOrBlock()
            this is FirWhenSubjectExpression -> {
                val lastSubjectVariable = subjectVariableStack.last()
                convertWithOffsets { startOffset, endOffset ->
                    IrGetValueImpl(startOffset, endOffset, lastSubjectVariable.type, lastSubjectVariable.symbol)
                }
            }
            else -> accept(this@Fir2IrVisitor, null) as IrExpression
        }
    }

    private fun FirBlock.convertToIrBlockBody(): IrBlockBody {
        return convertWithOffsets { startOffset, endOffset ->
            val irStatements = statements.map { it.toIrStatement() }
            IrBlockBodyImpl(
                startOffset, endOffset,
                if (irStatements.isNotEmpty()) {
                    irStatements.filterNotNull().takeIf { it.isNotEmpty() }
                        ?: listOf(IrBlockImpl(startOffset, endOffset, unitType, null, emptyList()))
                } else {
                    emptyList()
                }
            )
        }
    }

    private fun FirBlock.convertToIrExpressionOrBlock(): IrExpression {
        if (statements.size == 1) {
            val firStatement = statements.single()
            if (firStatement is FirExpression) {
                return firStatement.toIrExpression()
            }
        }
        val type =
            (statements.lastOrNull() as? FirExpression)?.typeRef?.toIrType(this@Fir2IrVisitor.session, declarationStorage) ?: unitType
        return convertWithOffsets { startOffset, endOffset ->
            IrBlockImpl(
                startOffset, endOffset, type, null,
                statements.mapNotNull { it.toIrStatement() }
            )
        }
    }

    override fun visitErrorExpression(errorExpression: FirErrorExpression, data: Any?): IrElement {
        return errorExpression.convertWithOffsets { startOffset, endOffset ->
            IrErrorExpressionImpl(
                startOffset, endOffset,
                errorExpression.typeRef.toIrType(session, declarationStorage),
                errorExpression.reason
            )
        }
    }

    private fun generateWhenSubjectVariable(whenExpression: FirWhenExpression): IrVariable? {
        val subjectVariable = whenExpression.subjectVariable
        val subjectExpression = whenExpression.subject
        return when {
            subjectVariable != null -> subjectVariable.accept(this, null) as IrVariable
            subjectExpression != null -> declarationStorage.declareTemporaryVariable(subjectExpression.toIrExpression(), "subject")
            else -> null
        }
    }

    override fun visitWhenExpression(whenExpression: FirWhenExpression, data: Any?): IrElement {
        val subjectVariable = generateWhenSubjectVariable(whenExpression)
        val origin = when (val psi = whenExpression.psi) {
            is KtWhenExpression -> IrStatementOrigin.WHEN
            is KtIfExpression -> IrStatementOrigin.IF
            is KtBinaryExpression -> when (psi.operationToken) {
                KtTokens.ELVIS -> IrStatementOrigin.ELVIS
                KtTokens.OROR -> IrStatementOrigin.OROR
                KtTokens.ANDAND -> IrStatementOrigin.ANDAND
                else -> null
            }
            is KtUnaryExpression -> IrStatementOrigin.EXCLEXCL
            else -> null
        }
        return subjectVariable.withSubject {
            whenExpression.convertWithOffsets { startOffset, endOffset ->
                val irWhen = IrWhenImpl(
                    startOffset, endOffset,
                    whenExpression.typeRef.toIrType(session, declarationStorage),
                    origin
                ).apply {
                    for (branch in whenExpression.branches) {
                        if (branch.condition !is FirElseIfTrueCondition || branch.result.statements.isNotEmpty()) {
                            branches += branch.accept(this@Fir2IrVisitor, data) as IrBranch
                        }
                    }
                }
                if (subjectVariable == null) {
                    irWhen
                } else {
                    IrBlockImpl(startOffset, endOffset, irWhen.type, origin, listOf(subjectVariable, irWhen))
                }
            }
        }
    }

    override fun visitWhenBranch(whenBranch: FirWhenBranch, data: Any?): IrElement {
        return whenBranch.convertWithOffsets { startOffset, endOffset ->
            val condition = whenBranch.condition
            val irResult = whenBranch.result.toIrExpression()
            if (condition is FirElseIfTrueCondition) {
                IrElseBranchImpl(IrConstImpl.boolean(irResult.startOffset, irResult.endOffset, booleanType, true), irResult)
            } else {
                IrBranchImpl(startOffset, endOffset, condition.toIrExpression(), irResult)
            }
        }
    }

    private val loopMap = mutableMapOf<FirLoop, IrLoop>()

    override fun visitDoWhileLoop(doWhileLoop: FirDoWhileLoop, data: Any?): IrElement {
        return doWhileLoop.convertWithOffsets { startOffset, endOffset ->
            IrDoWhileLoopImpl(
                startOffset, endOffset, unitType,
                IrStatementOrigin.DO_WHILE_LOOP
            ).apply {
                loopMap[doWhileLoop] = this
                label = doWhileLoop.label?.name
                condition = doWhileLoop.condition.toIrExpression()
                body = doWhileLoop.block.convertToIrExpressionOrBlock()
                loopMap.remove(doWhileLoop)
            }
        }
    }

    override fun visitWhileLoop(whileLoop: FirWhileLoop, data: Any?): IrElement {
        return whileLoop.convertWithOffsets { startOffset, endOffset ->
            IrWhileLoopImpl(
                startOffset, endOffset, unitType,
                if (whileLoop.psi is KtForExpression) IrStatementOrigin.FOR_LOOP_INNER_WHILE
                else IrStatementOrigin.WHILE_LOOP
            ).apply {
                loopMap[whileLoop] = this
                label = whileLoop.label?.name
                condition = whileLoop.condition.toIrExpression()
                body = whileLoop.block.convertToIrExpressionOrBlock()
                loopMap.remove(whileLoop)
            }
        }
    }

    private fun FirJump<FirLoop>.convertJumpWithOffsets(
        f: (startOffset: Int, endOffset: Int, irLoop: IrLoop) -> IrBreakContinueBase
    ): IrExpression {
        return convertWithOffsets { startOffset, endOffset ->
            val firLoop = target.labeledElement
            val irLoop = loopMap[firLoop]
            if (irLoop == null) {
                IrErrorExpressionImpl(startOffset, endOffset, nothingType, "Unbound loop: ${render()}")
            } else {
                f(startOffset, endOffset, irLoop).apply {
                    label = irLoop.label.takeIf { target.labelName != null }
                }
            }
        }
    }

    override fun visitBreakExpression(breakExpression: FirBreakExpression, data: Any?): IrElement {
        return breakExpression.convertJumpWithOffsets { startOffset, endOffset, irLoop ->
            IrBreakImpl(startOffset, endOffset, nothingType, irLoop)
        }
    }

    override fun visitContinueExpression(continueExpression: FirContinueExpression, data: Any?): IrElement {
        return continueExpression.convertJumpWithOffsets { startOffset, endOffset, irLoop ->
            IrContinueImpl(startOffset, endOffset, nothingType, irLoop)
        }
    }

    override fun visitThrowExpression(throwExpression: FirThrowExpression, data: Any?): IrElement {
        return throwExpression.convertWithOffsets { startOffset, endOffset ->
            IrThrowImpl(startOffset, endOffset, nothingType, throwExpression.exception.toIrExpression())
        }
    }

    override fun visitTryExpression(tryExpression: FirTryExpression, data: Any?): IrElement {
        return tryExpression.convertWithOffsets { startOffset, endOffset ->
            IrTryImpl(
                startOffset, endOffset, tryExpression.typeRef.toIrType(session, declarationStorage),
                tryExpression.tryBlock.convertToIrExpressionOrBlock(),
                tryExpression.catches.map { it.accept(this, data) as IrCatch },
                tryExpression.finallyBlock?.convertToIrExpressionOrBlock()
            )
        }
    }

    override fun visitCatch(catch: FirCatch, data: Any?): IrElement {
        return catch.convertWithOffsets { startOffset, endOffset ->
            IrCatchImpl(startOffset, endOffset, declarationStorage.createAndSaveIrVariable(catch.parameter)).apply {
                result = catch.block.convertToIrExpressionOrBlock()
            }
        }
    }

    override fun visitOperatorCall(operatorCall: FirOperatorCall, data: Any?): IrElement {
        return operatorCall.convertWithOffsets { startOffset, endOffset ->
            val (type, symbol) = when (operatorCall.operation) {
                FirOperation.EQ -> booleanType to irBuiltIns.eqeqSymbol
                FirOperation.NOT_EQ -> TODO()
                FirOperation.RANGE -> TODO()
                FirOperation.IDENTITY -> TODO()
                FirOperation.NOT_IDENTITY -> TODO()
                FirOperation.LT -> TODO()
                FirOperation.GT -> TODO()
                FirOperation.LT_EQ -> TODO()
                FirOperation.GT_EQ -> TODO()
                FirOperation.IN -> TODO()
                FirOperation.NOT_IN -> TODO()
                FirOperation.ASSIGN -> TODO()
                FirOperation.PLUS_ASSIGN -> TODO()
                FirOperation.MINUS_ASSIGN -> TODO()
                FirOperation.TIMES_ASSIGN -> TODO()
                FirOperation.DIV_ASSIGN -> TODO()
                FirOperation.REM_ASSIGN -> TODO()
                FirOperation.EXCL -> TODO()
                FirOperation.OTHER -> TODO()
                FirOperation.IS, FirOperation.NOT_IS,
                FirOperation.AS, FirOperation.SAFE_AS -> {
                    TODO("Should not be here")
                }
            }
            IrBinaryPrimitiveImpl(
                startOffset, endOffset, type, null, symbol,
                operatorCall.arguments[0].toIrExpression(),
                operatorCall.arguments[1].toIrExpression()
            )
        }
    }

    override fun visitTypeOperatorCall(typeOperatorCall: FirTypeOperatorCall, data: Any?): IrElement {
        return typeOperatorCall.convertWithOffsets { startOffset, endOffset ->
            val irTypeOperand = typeOperatorCall.conversionTypeRef.toIrType(session, declarationStorage)
            val (irType, irTypeOperator) = when (typeOperatorCall.operation) {
                FirOperation.IS -> booleanType to IrTypeOperator.INSTANCEOF
                FirOperation.NOT_IS -> booleanType to IrTypeOperator.NOT_INSTANCEOF
                FirOperation.AS -> irTypeOperand to IrTypeOperator.CAST
                FirOperation.SAFE_AS -> irTypeOperand.makeNullable() to IrTypeOperator.SAFE_CAST
                else -> TODO("Should not be here: ${typeOperatorCall.operation} in type operator call")
            }

            IrTypeOperatorCallImpl(
                startOffset, endOffset, irType, irTypeOperator, irTypeOperand,
                irTypeOperand.classifierOrFail, typeOperatorCall.argument.toIrExpression()
            )
        }
    }

    override fun visitGetClassCall(getClassCall: FirGetClassCall, data: Any?): IrElement {
        return getClassCall.convertWithOffsets { startOffset, endOffset ->
            IrGetClassImpl(
                startOffset, endOffset, getClassCall.typeRef.toIrType(session, declarationStorage),
                getClassCall.argument.toIrExpression()
            )
        }
    }
}