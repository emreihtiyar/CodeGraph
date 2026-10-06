/*
 * CodeGraph: grammars-v4 TypeScript parser'ının (javascript/typescript) TSX için genişletilmiş hali.
 * Değişiklik: singleExpression'a JSX elemanı ve JSX kuralları eklendi. Orijinal lisans aşağıdadır (MIT).
 */
/*
 * The MIT License (MIT)
 *
 * Copyright (c) 2014 by Bart Kiers (original author) and Alexandre Vitorelli (contributor -> ported to CSharp)
 * Copyright (c) 2017 by Ivan Kochurkin (Positive Technologies):
    added ECMAScript 6 support, cleared and transformed to the universal grammar.
 * Copyright (c) 2018 by Juan Alvarez (contributor -> ported to Go)
 * Copyright (c) 2019 by Andrii Artiushok (contributor -> added TypeScript support)
 * Copyright (c) 2024 by Andrew Leppard (www.wegrok.review)
 *
 * Permission is hereby granted, free of charge, to any person
 * obtaining a copy of this software and associated documentation
 * files (the "Software"), to deal in the Software without
 * restriction, including without limitation the rights to use,
 * copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the
 * Software is furnished to do so, subject to the following
 * conditions:
 *
 * The above copyright notice and this permission notice shall be
 * included in all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND,
 * EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES
 * OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND
 * NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT
 * HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY,
 * WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING
 * FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR
 * OTHER DEALINGS IN THE SOFTWARE.
 */

// $antlr-format alignTrailingComments true, columnLimit 150, minEmptyLines 1, maxEmptyLinesToKeep 1, reflowComments false, useTab false
// $antlr-format allowShortRulesOnASingleLine false, allowShortBlocksOnASingleLine true, alignSemicolons hanging, alignColons hanging

parser grammar TsxParser;

options {
    tokenVocab = TsxLexer;
    superClass = TsxParserBase;
}

// SupportSyntax

initializer
    : Assign singleExpression
    ;

bindingPattern
    : (arrayLiteral | objectLiteral)
    ;

// TypeScript SPart
// A.1 Types
typeParameters
    : LessThan typeParameterList? MoreThan
    ;

typeParameterList
    : typeParameter (',' typeParameter)* ','? // CodeGraph: TSX'te <T,> biçimi
    ;

typeParameter
    : identifier constraint?
    | identifier Assign typeArgument
    | typeParameters
    ;

constraint
    : 'extends' type_
    ;

typeArguments
    : LessThan typeArgumentList? MoreThan
    ;

typeArgumentList
    : typeArgument (',' typeArgument)*
    ;

typeArgument
    : type_
    ;

// CodeGraph: tip alt grameri modern TypeScript için yeniden yazıldı (koşullu/eşlemeli/şablon tipler, indeksli
// erişim, isimli tuple elemanları, infer, import() tipleri, tip yüklemleri). Çıkarım tipleri kullanmaz; amaç
// tiplerin hatasız atlanmasıdır.
type_
    : ('|' | '&')? conditionalType
    ;

conditionalType
    : unionType (Extends unionType '?' type_ ':' type_)?
    ;

unionType
    : intersectionType ('|' ('&')? intersectionType)*
    ;

intersectionType
    : typeOperatorType ('&' typeOperatorType)*
    ;

typeOperatorType
    : KeyOf typeOperatorType
    | Unique typeOperatorType
    | ReadOnly typeOperatorType
    | {this.n("infer")}? identifier identifier (Extends unionType)?
    | {this.n("asserts")}? identifier (identifier | This) (Is type_)?
    | (identifier | This) Is type_
    | postfixType
    ;

postfixType
    : primaryType ({this.notLineTerminator()}? '[' type_? ']')*
    ;

primaryType
    : '(' type_ ')'                                              # ParenthesizedPrimType
    | functionType                                               # FunctionPrimType
    | constructorType                                            # ConstructorPrimType
    | predefinedType                                             # PredefinedPrimType
    | typeQuery                                                  # QueryPrimType
    | This                                                       # ThisPrimType
    | '[' tupleElementTypes? ']'                                 # TuplePrimType
    | objectType                                                 # ObjectPrimType
    | templateStringLiteral                                      # TemplatePrimType
    | '-' (DecimalLiteral | numericLiteral)                      # NegativeLiteralPrimType
    | Import '(' StringLiteral ')' ('.' identifierName)* typeGeneric? # ImportPrimType
    | typeReference                                              # ReferencePrimType
    ;

predefinedType
    : Any
    | NullLiteral
    | Number
    | numericLiteral
    | bigintLiteral
    | Boolean
    | BooleanLiteral
    | String
    | StringLiteral
    | Symbol
    | Never
    | Undefined
    | Object
    | Void
    ;

typeReference
    : typeName typeGeneric?
    ;

typeGeneric
    : LessThan typeArgumentList MoreThan
    ;

typeName
    : identifierName ('.' identifierName)*
    ;

objectType
    : OpenBrace typeBody? CloseBrace
    ;

typeBody
    : typeMember ((SemiColon | ',')? typeMember)* (SemiColon | ',')?
    ;

typeMember
    : mappedTypeMember
    | indexSignature
    | constructSignature
    | callSignature
    | getAccessorSignature
    | methodSignature
    | propertySignatur
    ;

mappedTypeMember
    : ('+' | '-')? ReadOnly? '[' identifier In type_ (As type_)? ']' ('+' | '-')? '?'? typeAnnotation?
    ;

getAccessorSignature
    : {this.n("get") || this.n("set")}? identifier propertyName '(' parameterList? ')' typeAnnotation?
    ;

arrayType
    : primaryType {this.notLineTerminator()}? '[' ']'
    ;

tupleType
    : '[' tupleElementTypes ']'
    ;

tupleElementTypes
    : tupleElement (',' tupleElement)* ','?
    ;

tupleElement
    : Ellipsis? (identifierName '?'? ':')? type_ '?'?
    ;

functionType
    : Abstract? New? typeParameters? '(' parameterList? ')' '=>' type_
    ;

constructorType
    : Abstract? 'new' typeParameters? '(' parameterList? ')' '=>' type_
    ;

typeQuery
    : 'typeof' typeQueryExpression typeGeneric?
    ;

typeQueryExpression
    : Import '(' StringLiteral ')' ('.' identifierName)*
    | identifierName ('.' identifierName)*
    ;

propertySignatur
    : ReadOnly? propertyName '?'? typeAnnotation? ('=>' type_)?
    ;

typeAnnotation
    : ':' type_
    ;

callSignature
    : typeParameters? '(' parameterList? ')' typeAnnotation?
    ;

// Function parameter list can have a trailing comma.
// See https://github.com/Microsoft/TypeScript/issues/16152
parameterList
    : restParameter
    | parameter (',' parameter)* (',' restParameter)? ','?
    ;

requiredParameterList
    : requiredParameter (',' requiredParameter)*
    ;

parameter
    : requiredParameter
    | optionalParameter
    ;

optionalParameter
    : decoratorList? (
        accessibilityModifier? identifierOrPattern (
            '?' typeAnnotation?
            | typeAnnotation? initializer
        )
    )
    ;

restParameter
    : '...' singleExpression typeAnnotation?
    ;

requiredParameter
    : decoratorList? accessibilityModifier? identifierOrPattern typeAnnotation?
    ;

accessibilityModifier
    : Public
    | Private
    | Protected
    ;

identifierOrPattern
    : identifierName
    | bindingPattern
    ;

constructSignature
    : 'new' typeParameters? '(' parameterList? ')' typeAnnotation?
    ;

indexSignature
    : ReadOnly? '[' identifier ':' type_ ']' typeAnnotation
    ;

methodSignature
    : propertyName '?'? callSignature
    ;

typeAliasDeclaration
    : Export? 'type' identifier typeParameters? Assign type_ eos
    ;

constructorDeclaration
    : accessibilityModifier? Constructor '(' formalParameterList? ')' (
        (OpenBrace functionBody CloseBrace)
        | SemiColon
    )?
    ;

// A.5 Interface

interfaceDeclaration
    : Export? Declare? Interface identifier typeParameters? interfaceExtendsClause? objectType SemiColon?
    ;

interfaceExtendsClause
    : Extends classOrInterfaceTypeList
    ;

classOrInterfaceTypeList
    : typeReference (',' typeReference)*
    ;

// A.7 Interface

enumDeclaration
    : Const? Enum identifier OpenBrace enumBody? CloseBrace
    ;

enumBody
    : enumMemberList ','?
    ;

enumMemberList
    : enumMember (',' enumMember)*
    ;

enumMember
    : propertyName (Assign singleExpression)?
    ;

// A.8 Namespaces

namespaceDeclaration
    : Declare? Namespace namespaceName OpenBrace statementList? CloseBrace
    ;

namespaceName
    : identifier ('.'+ identifier)*
    ;

importAliasDeclaration
    : identifier Assign namespaceName SemiColon
    ;

// Ext.2 Additions to 1.8: Decorators

decoratorList
    : decorator+
    ;

decorator
    : '@' (decoratorMemberExpression | decoratorCallExpression)
    ;

decoratorMemberExpression
    : identifier
    | decoratorMemberExpression '.' identifierName
    | '(' singleExpression ')'
    ;

decoratorCallExpression
    : decoratorMemberExpression arguments
    ;

// ECMAPart
program
    : sourceElements? EOF
    ;

// CodeGraph: `Export?` kaldırıldı; export'lar exportStatement veya `Export statement` ile ayrışır
sourceElement
    : statement
    ;

statement
    : block
    | namespaceDeclaration //ADDED
    | variableStatement
    | importStatement
    | exportStatement
    | emptyStatement_
    | abstractDeclaration //ADDED
    | classDeclaration
    | functionDeclaration
    | interfaceDeclaration //ADDED
    | ifStatement
    | iterationStatement
    | continueStatement
    | breakStatement
    | returnStatement
    | yieldStatement
    | withStatement
    | labelledStatement
    | switchStatement
    | throwStatement
    | tryStatement
    | debuggerStatement
    | arrowFunctionDeclaration
    | generatorFunctionDeclaration
    | typeAliasDeclaration //ADDED
    | enumDeclaration      //ADDED
    | Export {!this.n("default")}? statement // CodeGraph: `export default` exportStatement'a kalır
    | expressionStatement
    ;

block
    : OpenBrace statementList? CloseBrace
    ;

statementList
    : statement+
    ;

abstractDeclaration
    : Abstract (identifier callSignature | variableStatement) eos
    ;

importStatement
    : Import importFromBlock
    ;

importFromBlock
    : TypeAlias? importDefault? (importNamespace | importModuleItems) importFrom eos // CodeGraph: import type
    | StringLiteral eos
    ;

importModuleItems
    : OpenBrace (importAliasName ',')* (importAliasName ','?)? CloseBrace
    ;

importAliasName
    : TypeAlias? moduleExportName (As importedBinding)? // CodeGraph: { type X }
    ;

moduleExportName
    : identifierName
    | StringLiteral
    ;

// yield and await are permitted as BindingIdentifier in the grammar
importedBinding
    : Identifier
    | Yield
    | Await
    ;

importDefault
    : aliasName ','
    ;

importNamespace
    : ('*' | identifierName) (As identifierName)?
    ;

importFrom
    : From StringLiteral
    ;

aliasName
    : identifierName (As identifierName)?
    ;

exportStatement
    : Export Default? (exportFromBlock | declaration) eos # ExportDeclaration
    | Export Default singleExpression eos                 # ExportDefaultDeclaration
    ;

exportFromBlock
    : importNamespace importFrom
    | TypeAlias? exportModuleItems importFrom? // CodeGraph: export type { X }; eos exportStatement'ta (çift eos kaldırıldı)
    ;

exportModuleItems
    : OpenBrace (exportAliasName ',')* (exportAliasName ','?)? CloseBrace
    ;

exportAliasName
    : TypeAlias? moduleExportName (As moduleExportName)?
    ;

declaration
    : variableStatement
    | classDeclaration
    | functionDeclaration
    ;

// CodeGraph: varModifier zorunlu; aksi halde her atama da bir değişken bildirimi adayı olur ve
// statement kararı çok uzun ileri bakışa (LL) düşer. Anahtar sözcüksüz yapı bozma atamaları ifade olarak ayrışır.
variableStatement
    : varModifier variableDeclarationList SemiColon?
    | Declare varModifier? variableDeclarationList SemiColon?
    ;

variableDeclarationList
    : variableDeclaration (',' variableDeclaration)*
    ;

variableDeclaration
    : (identifierOrKeyWord | arrayLiteral | objectLiteral) '!'? typeAnnotation? (
        Assign typeParameters? singleExpression
    )? // ECMAScript 6: Array & Object Matching; CodeGraph: başıboş singleExpression? kaldırıldı, `let x!: T` eklendi
    ;

emptyStatement_
    : SemiColon
    ;

expressionStatement
    : {this.notOpenBraceAndNotFunctionAndNotInterface()}? expressionSequence SemiColon?
    ;

ifStatement
    : If '(' expressionSequence ')' statement (Else statement)?
    ;

iterationStatement
    : Do statement While '(' expressionSequence ')' eos                                                                     # DoStatement
    | While '(' expressionSequence ')' statement                                                                            # WhileStatement
    | For '(' expressionSequence? SemiColon expressionSequence? SemiColon expressionSequence? ')' statement                 # ForStatement
    | For '(' varModifier variableDeclarationList SemiColon expressionSequence? SemiColon expressionSequence? ')' statement # ForVarStatement
    | For '(' singleExpression In expressionSequence ')' statement                                                          # ForInStatement
    | For '(' varModifier variableDeclaration In expressionSequence ')' statement                                           # ForVarInStatement
    | For Await? '(' singleExpression identifier {this.p("of")}? expressionSequence ')' statement                # ForOfStatement
    | For Await? '(' varModifier variableDeclaration identifier {this.p("of")}? expressionSequence ')' statement # ForVarOfStatement
    ;

varModifier
    : Var
    | Let
    | Const
    ;

continueStatement
    : Continue ({this.notLineTerminator()}? identifier)? eos
    ;

breakStatement
    : Break ({this.notLineTerminator()}? identifier)? eos
    ;

returnStatement
    : Return ({this.notLineTerminator()}? expressionSequence)? eos
    ;

yieldStatement
    : (Yield | YieldStar) ({this.notLineTerminator()}? expressionSequence)? eos
    ;

withStatement
    : With '(' expressionSequence ')' statement
    ;

switchStatement
    : Switch '(' expressionSequence ')' caseBlock
    ;

caseBlock
    : OpenBrace caseClauses? (defaultClause caseClauses?)? CloseBrace
    ;

caseClauses
    : caseClause+
    ;

caseClause
    : Case expressionSequence ':' statementList?
    ;

defaultClause
    : Default ':' statementList?
    ;

labelledStatement
    : identifier ':' statement
    ;

throwStatement
    : Throw {this.notLineTerminator()}? expressionSequence eos
    ;

tryStatement
    : Try block (catchProduction finallyProduction? | finallyProduction)
    ;

catchProduction
    : Catch ('(' identifier typeAnnotation? ')')? block
    ;

finallyProduction
    : Finally block
    ;

debuggerStatement
    : Debugger eos
    ;

functionDeclaration
    : Async? Function_ '*'? identifier callSignature ((OpenBrace functionBody CloseBrace) | SemiColon)
    ;

//Ovveride ECMA
classDeclaration
    : decoratorList? (Export Default?)? Abstract? Class identifier typeParameters? classHeritage classTail
    ;

classHeritage
    : classExtendsClause? implementsClause?
    ;

classTail
    : OpenBrace classElement* CloseBrace
    ;

classExtendsClause
    : Extends typeReference
    ;

implementsClause
    : Implements classOrInterfaceTypeList
    ;

// Classes modified
classElement
    : constructorDeclaration
    | decoratorList? propertyMemberDeclaration
    | indexMemberDeclaration
    | statement
    ;

propertyMemberDeclaration
    : propertyMemberBase propertyName '?'? typeAnnotation? initializer? SemiColon        # PropertyDeclarationExpression
    | propertyMemberBase propertyName callSignature ((OpenBrace functionBody CloseBrace) | SemiColon) # MethodDeclarationExpression
    | propertyMemberBase (getAccessor | setAccessor)                                     # GetterSetterDeclarationExpression
    | abstractDeclaration                                                                # AbstractMemberDeclaration
    ;

propertyMemberBase
    : accessibilityModifier? Async? Static? ReadOnly?
    ;

indexMemberDeclaration
    : indexSignature SemiColon
    ;

generatorMethod
    : (Async {this.notLineTerminator()}?)? '*'? propertyName '(' formalParameterList? ')' OpenBrace functionBody CloseBrace
    ;

generatorFunctionDeclaration
    : Async? Function_ '*' identifier? '(' formalParameterList? ')' OpenBrace functionBody CloseBrace
    ;

generatorBlock
    : OpenBrace generatorDefinition (',' generatorDefinition)* ','? CloseBrace
    ;

generatorDefinition
    : '*' iteratorDefinition
    ;

iteratorBlock
    : OpenBrace iteratorDefinition (',' iteratorDefinition)* ','? CloseBrace
    ;

iteratorDefinition
    : '[' singleExpression ']' '(' formalParameterList? ')' OpenBrace functionBody CloseBrace
    ;

classElementName
    : propertyName
    | privateIdentifier
    ;

privateIdentifier
    : '#' identifierName
    ;

formalParameterList
    : formalParameterArg (',' formalParameterArg)* (',' lastFormalParameterArg)? ','?
    | lastFormalParameterArg
    | arrayLiteral                             // ECMAScript 6: Parameter Context Matching
    | objectLiteral (':' formalParameterList)? // ECMAScript 6: Parameter Context Matching
    ;

formalParameterArg
    : decorator? accessibilityModifier? assignable '?'? typeAnnotation? (
        Assign singleExpression
    )? // ECMAScript 6: Initialization
    ;

lastFormalParameterArg // ECMAScript 6: Rest Parameter
    : Ellipsis identifier typeAnnotation?
    ;

functionBody
    : sourceElements?
    ;

sourceElements
    : sourceElement+
    ;

arrayLiteral
    : ('[' elementList ']')
    ;

// JavaScript supports arrasys like [,,1,2,,].
elementList
    : ','* arrayElement? (','+ arrayElement) * ','* // Yes, everything is optional
    ;

arrayElement // ECMAScript 6: Spread Operator
    : Ellipsis? (singleExpression | identifier) ','?
    ;

objectLiteral
    : OpenBrace (propertyAssignment (',' propertyAssignment)* ','?)? CloseBrace
    ;

// MODIFIED
propertyAssignment
    : propertyName (':' | Assign) singleExpression     # PropertyExpressionAssignment
    | '[' singleExpression ']' ':' singleExpression # ComputedPropertyExpressionAssignment
    | getAccessor                                   # PropertyGetter
    | setAccessor                                   # PropertySetter
    | generatorMethod                               # MethodProperty
    | identifierOrKeyWord                           # PropertyShorthand
    | Ellipsis? singleExpression                    # SpreadOperator
    | restParameter                                 # RestParameterInObject
    ;

getAccessor
    : getter '(' ')' typeAnnotation? OpenBrace functionBody CloseBrace
    ;

setAccessor
    : setter '(' formalParameterList? ')' OpenBrace functionBody CloseBrace
    ;

propertyName
    : identifierName
    | StringLiteral
    | numericLiteral
    | '[' singleExpression ']'
    ;

arguments
    : '(' (argumentList ','?)? ')'
    ;

argumentList
    : argument (',' argument)*
    ;

argument // ECMAScript 6: Spread Operator
    : Ellipsis? (singleExpression | identifier)
    ;

expressionSequence
    : singleExpression (',' singleExpression)*
    ;

singleExpression
    : anonymousFunction                                           # FunctionExpression
    | Class identifier? typeParameters? classHeritage classTail   # ClassExpression
    | singleExpression '?.'? '[' expressionSequence ']'           # MemberIndexExpression
    | singleExpression '?.' '#'? identifierName                   # OptionalChainExpression
    | singleExpression '!'? '.' '#'? identifierName                # MemberDotExpression
    | singleExpression '?'? '.' '#'? identifierName                # MemberDotExpression
    // Split to try `new Date()` first, then `new Date`.
    | New newCallee typeArguments? arguments?                         # NewExpression // CodeGraph: callee daraltıldı (SLL belirsizliği)
    | singleExpression '?.'? typeArguments? arguments                 # ArgumentsExpression
    | singleExpression {this.notLineTerminator()}? '++'               # PostIncrementExpression
    | singleExpression {this.notLineTerminator()}? '--'               # PostDecreaseExpression
    | Delete singleExpression                                         # DeleteExpression
    | Void singleExpression                                           # VoidExpression
    | Typeof singleExpression                                         # TypeofExpression
    | '++' singleExpression                                           # PreIncrementExpression
    | '--' singleExpression                                           # PreDecreaseExpression
    | '+' singleExpression                                            # UnaryPlusExpression
    | '-' singleExpression                                            # UnaryMinusExpression
    | '~' singleExpression                                            # BitNotExpression
    | '!' singleExpression                                            # NotExpression
    | Await singleExpression                                          # AwaitExpression
    | <assoc = right> singleExpression '**' singleExpression          # PowerExpression
    | singleExpression ('*' | '/' | '%') singleExpression             # MultiplicativeExpression
    | singleExpression ('+' | '-') singleExpression                   # AdditiveExpression
    | singleExpression '??' singleExpression                          # CoalesceExpression
    | singleExpression ('<<' | MoreThan MoreThan | MoreThan MoreThan MoreThan) singleExpression # BitShiftExpression
    | singleExpression {!this.typeArgumentsAhead()}? (LessThan | MoreThan | '<=' | '>=') singleExpression # RelationalExpression
    | singleExpression Instanceof singleExpression                    # InstanceofExpression
    | singleExpression In singleExpression                            # InExpression
    | singleExpression ('==' | '!=' | '===' | '!==') singleExpression # EqualityExpression
    | singleExpression '&' singleExpression                           # BitAndExpression
    | singleExpression '^' singleExpression                           # BitXOrExpression
    | singleExpression '|' singleExpression                           # BitOrExpression
    | singleExpression '&&' singleExpression                          # LogicalAndExpression
    | singleExpression '||' singleExpression                          # LogicalOrExpression
    | singleExpression '?' singleExpression ':' singleExpression      # TernaryExpression
    | singleExpression Assign singleExpression                           # AssignmentExpression
    | singleExpression assignmentOperator singleExpression            # AssignmentOperatorExpression
    | singleExpression templateStringLiteral                          # TemplateStringExpression     // ECMAScript 6
    | iteratorBlock                                                   # IteratorsExpression          // ECMAScript 6
    | generatorBlock                                                  # GeneratorsExpression         // ECMAScript 6
    | generatorFunctionDeclaration                                    # GeneratorsFunctionExpression // ECMAScript 6
    | yieldStatement                                                  # YieldExpression              // ECMAScript 6
    | This                                                            # ThisExpression
    | identifierName                                                  # IdentifierExpression
    | Super                                                           # SuperExpression
    | literal                                                         # LiteralExpression
    | jsxElement                                                      # JsxElementExpression
    | arrayLiteral                                                    # ArrayLiteralExpression
    | objectLiteral                                                   # ObjectLiteralExpression
    | '(' expressionSequence ')'                                      # ParenthesizedExpression
    | singleExpression As (Const | type_)                             # CastAsExpression
    | singleExpression Satisfies type_                                # SatisfiesExpression
// TypeScript v2.0
    | singleExpression '!'                                            # NonNullAssertionExpression
    ;


assignable
    : identifier
    | keyword
    | arrayLiteral
    | objectLiteral
    ;

anonymousFunction
    : functionDeclaration
    | Async? Function_ '*'? '(' formalParameterList? ')' typeAnnotation? OpenBrace functionBody CloseBrace
    | arrowFunctionDeclaration
    ;

// CodeGraph: `new` hedefi; çağrı içermeyen üye zinciri. `new Map<K, V>()` gibi ifadelerde SLL'nin
// iç ifadeyi çağrı sanmasını önler.
newCallee
    : (identifierName | This | '(' singleExpression ')' | New newCallee) ('.' '#'? identifierName | '[' expressionSequence ']')*
    ;

// CodeGraph: parantezsiz parametre yalnızca tek tanımlayıcı olabilir ve dönüş tipi alamaz; aksi halde
// `a ? null : b` gibi koşullu ifadeler SLL'de ok fonksiyonu sanılır. Generic ok fonksiyonu (`<T,>(x) => x`) eklendi.
arrowFunctionDeclaration
    : Async? identifier '=>' arrowFunctionBody
    | Async? typeParameters? arrowFunctionParameters typeAnnotation? '=>' arrowFunctionBody
    ;

arrowFunctionParameters
    : '(' formalParameterList? ')'
    ;

arrowFunctionBody
    : singleExpression
    | OpenBrace functionBody CloseBrace
    ;

assignmentOperator
    : '*='
    | '/='
    | '%='
    | '+='
    | '-='
    | '<<='
    | '>>='
    | '>>>='
    | '&='
    | '^='
    | '|='
    | '**='
    | '??='
    ;

literal
    : NullLiteral
    | BooleanLiteral
    | StringLiteral
    | templateStringLiteral
    | RegularExpressionLiteral
    | numericLiteral
    | bigintLiteral
    ;

templateStringLiteral
    : BackTick templateStringAtom* BackTick
    ;

templateStringAtom
    : TemplateStringAtom
    | TemplateStringStartExpression singleExpression TemplateCloseBrace
    | TemplateStringEscapeAtom
    ;

numericLiteral
    : DecimalLiteral
    | HexIntegerLiteral
    | OctalIntegerLiteral
    | OctalIntegerLiteral2
    | BinaryIntegerLiteral
    ;

bigintLiteral
    : BigDecimalIntegerLiteral
    | BigHexIntegerLiteral
    | BigOctalIntegerLiteral
    | BigBinaryIntegerLiteral
    ;

getter
    : {this.n("get")}? identifier classElementName
    ;

setter
    : {this.n("set")}? identifier classElementName
    ;

identifierName
    : identifier
    | reservedWord
    ;

identifier
    : Identifier
    | Satisfies
    | Declare
    | Module
    | Require
    | Is
    | Async
    | As
    | From
    | Yield
    | Of
    | Any
    | Any
    | Number
    | Boolean
    | String
    | Unique
    | Symbol
    | Never
    | Undefined
    | Object
    | KeyOf
    | TypeAlias
    | Constructor
    | Namespace
    | Abstract
    ;

identifierOrKeyWord
    : identifier
    | TypeAlias
    | Require
    ;

reservedWord
    : keyword
    | NullLiteral
    | BooleanLiteral
    ;

keyword
    : Break
    | Do
    | Instanceof
    | Typeof
    | Case
    | Else
    | New
    | Var
    | Catch
    | Finally
    | Return
    | Void
    | Continue
    | For
    | Switch
    | While
    | Debugger
    | Function_
    | This
    | With
    | Default
    | If
    | Throw
    | Delete
    | In
    | Try
    | Class
    | Enum
    | Extends
    | Super
    | Const
    | Export
    | Import
    | Implements
    | Let
    | Private
    | Public
    | Interface
    | Package
    | Protected
    | Static
    | Yield
    | Async
    | Await
    | ReadOnly
    | From
    | As
    | Require
    | TypeAlias
    | String
    | Boolean
    | Number
    | Module
    ;

eos
    : SemiColon
    | EOF
    | {this.lineTerminatorAhead()}?
    | {this.closeBrace()}?
    ;

// ---------------------------------------------------------------- JSX (CodeGraph)

jsxElement
    : jsxSelfClosingElement
    | jsxOpeningElement jsxChild* jsxClosingElement
    ;

jsxElementBegin
    : JsxElementBegin
    | JsxOpeningElementBegin
    | JsxChildrenOpeningElementBegin
    ;

jsxSelfClosingElement
    : jsxElementBegin JsxOpeningElementId jsxAttribute* JsxOpeningElementSlashEnd
    ;

// Ad yoksa fragment: <>...</>
jsxOpeningElement
    : jsxElementBegin JsxOpeningElementId? jsxAttribute* JsxOpeningElementEnd
    ;

jsxClosingElement
    : JsxChildrenClosingElementSlashBegin JsxClosingElementId? JsxClosingElementEnd
    ;

jsxChild
    : HtmlChardata
    | jsxElement
    | jsxExpressionContainer
    ;

jsxAttribute
    : JsxOpeningElementOpenBrace Ellipsis singleExpression CloseBrace # JsxSpreadAttribute
    | JsxOpeningElementId (JsxAssign jsxAttributeValue)?              # JsxNamedAttribute
    ;

jsxAttributeValue
    : JsxAttributeValue
    | jsxElement
    | jsxExpressionContainer
    ;

// Boş kap {/* yorum */} geçerlidir
jsxExpressionContainer
    : (JsxOpeningElementOpenBrace | JsxChildrenOpenBrace) (Ellipsis? expressionSequence)? CloseBrace
    ;
