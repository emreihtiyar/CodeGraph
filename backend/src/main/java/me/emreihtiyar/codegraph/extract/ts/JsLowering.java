package me.emreihtiyar.codegraph.extract.ts;

import java.util.ArrayList;
import java.util.List;
import me.emreihtiyar.codegraph.extract.ts.Js.Arr;
import me.emreihtiyar.codegraph.extract.ts.Js.Binary;
import me.emreihtiyar.codegraph.extract.ts.Js.Call;
import me.emreihtiyar.codegraph.extract.ts.Js.ClassNode;
import me.emreihtiyar.codegraph.extract.ts.Js.Cond;
import me.emreihtiyar.codegraph.extract.ts.Js.Export;
import me.emreihtiyar.codegraph.extract.ts.Js.ExportFrom;
import me.emreihtiyar.codegraph.extract.ts.Js.ExportSpec;
import me.emreihtiyar.codegraph.extract.ts.Js.Func;
import me.emreihtiyar.codegraph.extract.ts.Js.Ident;
import me.emreihtiyar.codegraph.extract.ts.Js.Import;
import me.emreihtiyar.codegraph.extract.ts.Js.ImportBinding;
import me.emreihtiyar.codegraph.extract.ts.Js.Index;
import me.emreihtiyar.codegraph.extract.ts.Js.Jsx;
import me.emreihtiyar.codegraph.extract.ts.Js.JsxAttr;
import me.emreihtiyar.codegraph.extract.ts.Js.Literal;
import me.emreihtiyar.codegraph.extract.ts.Js.Member;
import me.emreihtiyar.codegraph.extract.ts.Js.New;
import me.emreihtiyar.codegraph.extract.ts.Js.Node;
import me.emreihtiyar.codegraph.extract.ts.Js.Obj;
import me.emreihtiyar.codegraph.extract.ts.Js.Other;
import me.emreihtiyar.codegraph.extract.ts.Js.Pattern;
import me.emreihtiyar.codegraph.extract.ts.Js.Prop;
import me.emreihtiyar.codegraph.extract.ts.Js.Return;
import me.emreihtiyar.codegraph.extract.ts.Js.Spread;
import me.emreihtiyar.codegraph.extract.ts.Js.Str;
import me.emreihtiyar.codegraph.extract.ts.Js.Template;
import me.emreihtiyar.codegraph.extract.ts.Js.Unary;
import me.emreihtiyar.codegraph.extract.ts.Js.VarDecl;
import me.emreihtiyar.codegraph.extract.ts.parser.TsxParser;
import me.emreihtiyar.codegraph.extract.ts.parser.TsxParser.*;
import org.antlr.v4.runtime.ParserRuleContext;
import org.antlr.v4.runtime.Token;
import org.antlr.v4.runtime.tree.ErrorNode;
import org.antlr.v4.runtime.tree.ParseTree;
import org.antlr.v4.runtime.tree.TerminalNode;

/**
 * {@link TsxParser} ağacını {@link Js} sözdizimi ağacına indirger. Tip bildirimleri (arayüz, tip takma adı, tip
 * notasyonları) atılır; tanınmayan yapılar {@link Other} olarak çocuklarıyla korunur.
 *
 * <p>Sözdizimi hatalı dosyalarda ANTLR'ın hata toparlaması eksik çocuklu düğümler bırakır. Böyle bir alt ağaç
 * indirgenemezse yalnızca o alt ağaç {@link Other} olarak çocuklarından kurtarılır; dosyanın geri kalanı korunur.
 * Hatasız ağaçtaki bir indirgeme hatası ise hata olarak yukarı iletilir.
 */
final class JsLowering {

    private JsLowering() {}

    static List<Node> program(ProgramContext program) {
        List<Node> out = new ArrayList<>();
        if (program.sourceElements() != null) {
            for (SourceElementContext element : program.sourceElements().sourceElement()) {
                out.addAll(sourceElement(element));
            }
        }
        return out;
    }

    private static List<Node> sourceElement(SourceElementContext ctx) {
        return statement(ctx.statement());
    }

    private static List<Node> exported(List<Node> nodes, boolean isDefault, ParserRuleContext ctx) {
        if (nodes.isEmpty() || nodes.get(0) instanceof Export || nodes.get(0) instanceof ExportFrom) {
            return nodes;
        }
        return List.of(new Export(nodes, isDefault, line(ctx)));
    }

    // ---- deyimler ----

    static List<Node> statement(StatementContext ctx) {
        if (ctx == null) {
            return List.of();
        }
        try {
            return lowerStatement(ctx);
        } catch (RuntimeException e) {
            return List.of(recover(ctx, e));
        }
    }

    private static List<Node> lowerStatement(StatementContext ctx) {
        if (ctx.statement() != null) { // Export statement
            return exported(statement(ctx.statement()), false, ctx);
        }
        if (ctx.variableStatement() != null) {
            return variableStatement(ctx.variableStatement());
        }
        if (ctx.importStatement() != null) {
            return importStatement(ctx.importStatement());
        }
        if (ctx.exportStatement() != null) {
            return exportStatement(ctx.exportStatement());
        }
        if (ctx.classDeclaration() != null) {
            ClassDeclarationContext c = ctx.classDeclaration();
            ClassNode node = classNode(c.identifier() == null ? null : c.identifier().getText(), c.classHeritage(),
                    c.classTail(), c);
            return c.Export() != null ? List.of(new Export(List.of(node), c.Default() != null, line(c))) : List.of(node);
        }
        if (ctx.functionDeclaration() != null) {
            return signatureOnly(ctx.functionDeclaration()) ? List.of() : List.of(functionDeclaration(ctx.functionDeclaration()));
        }
        if (ctx.returnStatement() != null) {
            ReturnStatementContext r = ctx.returnStatement();
            return List.of(new Return(r.expressionSequence() == null ? null : sequence(r.expressionSequence()), line(r)));
        }
        if (ctx.arrowFunctionDeclaration() != null) {
            return List.of(arrow(ctx.arrowFunctionDeclaration()));
        }
        if (ctx.expressionStatement() != null) {
            return List.of(sequence(ctx.expressionStatement().expressionSequence()));
        }
        if (ctx.interfaceDeclaration() != null || ctx.typeAliasDeclaration() != null || ctx.emptyStatement_() != null) {
            return List.of();
        }
        if (ctx.block() != null) {
            return List.of(new Other(statements(ctx.block().statementList()), line(ctx)));
        }
        return List.of(generic(ctx));
    }

    private static List<Node> statements(StatementListContext list) {
        List<Node> out = new ArrayList<>();
        if (list != null) {
            for (StatementContext s : list.statement()) {
                out.addAll(statement(s));
            }
        }
        return out;
    }

    private static List<Node> functionBody(FunctionBodyContext body) {
        List<Node> out = new ArrayList<>();
        if (body != null && body.sourceElements() != null) {
            for (SourceElementContext element : body.sourceElements().sourceElement()) {
                out.addAll(sourceElement(element));
            }
        }
        return out;
    }

    private static List<Node> variableStatement(VariableStatementContext ctx) {
        List<Node> out = new ArrayList<>();
        for (VariableDeclarationContext d : ctx.variableDeclarationList().variableDeclaration()) {
            Pattern target;
            if (d.identifierOrKeyWord() != null) {
                target = new Pattern(d.identifierOrKeyWord().getText(), List.of(d.identifierOrKeyWord().getText()));
            } else {
                Node pattern = d.arrayLiteral() != null ? arrayLiteral(d.arrayLiteral()) : objectLiteral(d.objectLiteral());
                target = new Pattern(null, patternNames(pattern));
            }
            Node init = d.singleExpression() == null ? null : expr(d.singleExpression());
            out.add(new VarDecl(target, init, line(d), endLine(d)));
        }
        return out;
    }

    private static List<Node> importStatement(ImportStatementContext ctx) {
        ImportFromBlockContext block = ctx.importFromBlock();
        if (block.StringLiteral() != null) { // import './side-effect'
            return List.of(new Import(unquote(block.StringLiteral().getText()), List.of(), line(ctx)));
        }
        if (block.TypeAlias() != null) {
            return List.of();
        }
        List<ImportBinding> bindings = new ArrayList<>();
        if (block.importDefault() != null) {
            AliasNameContext alias = block.importDefault().aliasName();
            bindings.add(new ImportBinding(alias.identifierName(alias.identifierName().size() - 1).getText(), "default"));
        }
        if (block.importNamespace() != null) {
            ImportNamespaceContext ns = block.importNamespace();
            String local = ns.identifierName(ns.identifierName().size() - 1).getText();
            if (ns.Multiply() != null) {
                bindings.add(new ImportBinding(local, "*"));
            } else { // import X from (varsayılan import, importDefault olmadan)
                bindings.add(new ImportBinding(local, "default"));
            }
        }
        if (block.importModuleItems() != null) {
            for (ImportAliasNameContext item : block.importModuleItems().importAliasName()) {
                if (item.TypeAlias() != null) {
                    continue;
                }
                String imported = moduleExportName(item.moduleExportName());
                String local = item.importedBinding() != null ? item.importedBinding().getText() : imported;
                bindings.add(new ImportBinding(local, imported));
            }
        }
        return List.of(new Import(unquote(block.importFrom().StringLiteral().getText()), bindings, line(ctx)));
    }

    private static List<Node> exportStatement(ExportStatementContext ctx) {
        if (ctx instanceof ExportDefaultDeclarationContext d) {
            return List.of(new Export(List.of(expr(d.singleExpression())), true, line(d)));
        }
        ExportDeclarationContext d = (ExportDeclarationContext) ctx;
        boolean isDefault = d.Default() != null;
        if (d.declaration() != null) {
            DeclarationContext decl = d.declaration();
            List<Node> nodes;
            if (decl.variableStatement() != null) {
                nodes = variableStatement(decl.variableStatement());
            } else if (decl.classDeclaration() != null) {
                ClassDeclarationContext c = decl.classDeclaration();
                nodes = List.of(classNode(c.identifier() == null ? null : c.identifier().getText(), c.classHeritage(),
                        c.classTail(), c));
            } else if (signatureOnly(decl.functionDeclaration())) {
                return List.of();
            } else {
                nodes = List.of(functionDeclaration(decl.functionDeclaration()));
            }
            return List.of(new Export(nodes, isDefault, line(d)));
        }
        ExportFromBlockContext from = d.exportFromBlock();
        if (from.TypeAlias() != null) {
            return List.of();
        }
        String source = from.importFrom() == null ? null : unquote(from.importFrom().StringLiteral().getText());
        if (from.importNamespace() != null) {
            ImportNamespaceContext ns = from.importNamespace();
            if (ns.As() != null) {
                return List.of(new ExportFrom(source, List.of(new ExportSpec("*", ns.identifierName(0).getText())), false, line(d)));
            }
            return List.of(new ExportFrom(source, List.of(), true, line(d)));
        }
        List<ExportSpec> specs = new ArrayList<>();
        for (ExportAliasNameContext item : from.exportModuleItems().exportAliasName()) {
            if (item.TypeAlias() != null) {
                continue;
            }
            String local = moduleExportName(item.moduleExportName(0));
            String exported = item.moduleExportName().size() > 1 ? moduleExportName(item.moduleExportName(1)) : local;
            specs.add(new ExportSpec(local, exported));
        }
        return List.of(new ExportFrom(source, specs, false, line(d)));
    }

    private static String moduleExportName(ModuleExportNameContext ctx) {
        return ctx.StringLiteral() != null ? unquote(ctx.StringLiteral().getText()) : ctx.getText();
    }

    // ---- fonksiyonlar ve sınıflar ----

    /** Gövdesiz aşırı yükleme imzası ya da {@code declare function}: asıl tanımı gölgelememesi için atılır. */
    private static boolean signatureOnly(FunctionDeclarationContext ctx) {
        return ctx.OpenBrace() == null;
    }

    private static Func functionDeclaration(FunctionDeclarationContext ctx) {
        CallSignatureContext sig = ctx.callSignature();
        return new Func(ctx.identifier().getText(), parameterList(sig.parameterList()), functionBody(ctx.functionBody()),
                ctx.Async() != null, line(ctx), endLine(ctx));
    }

    private static Func anonymousFunction(AnonymousFunctionContext ctx) {
        if (ctx.functionDeclaration() != null) {
            return functionDeclaration(ctx.functionDeclaration());
        }
        if (ctx.arrowFunctionDeclaration() != null) {
            return arrow(ctx.arrowFunctionDeclaration());
        }
        return new Func(null, formalParameterList(ctx.formalParameterList()), functionBody(ctx.functionBody()),
                ctx.Async() != null, line(ctx), endLine(ctx));
    }

    private static Func arrow(ArrowFunctionDeclarationContext ctx) {
        List<Pattern> params;
        if (ctx.identifier() != null) {
            params = List.of(new Pattern(ctx.identifier().getText(), List.of(ctx.identifier().getText())));
        } else {
            params = formalParameterList(ctx.arrowFunctionParameters().formalParameterList());
        }
        ArrowFunctionBodyContext body = ctx.arrowFunctionBody();
        List<Node> statements = body.singleExpression() != null
                ? List.of(new Return(expr(body.singleExpression()), line(body)))
                : functionBody(body.functionBody());
        return new Func(null, params, statements, ctx.Async() != null, line(ctx), endLine(ctx));
    }

    private static List<Pattern> parameterList(ParameterListContext ctx) {
        List<Pattern> out = new ArrayList<>();
        if (ctx == null) {
            return out;
        }
        for (ParameterContext p : ctx.parameter()) {
            IdentifierOrPatternContext id = p.requiredParameter() != null
                    ? p.requiredParameter().identifierOrPattern()
                    : p.optionalParameter().identifierOrPattern();
            out.add(identifierOrPattern(id));
        }
        if (ctx.restParameter() != null) {
            out.add(patternOf(expr(ctx.restParameter().singleExpression())));
        }
        return out;
    }

    private static Pattern identifierOrPattern(IdentifierOrPatternContext ctx) {
        if (ctx.identifierName() != null) {
            return new Pattern(ctx.identifierName().getText(), List.of(ctx.identifierName().getText()));
        }
        BindingPatternContext b = ctx.bindingPattern();
        Node pattern = b.arrayLiteral() != null ? arrayLiteral(b.arrayLiteral()) : objectLiteral(b.objectLiteral());
        return new Pattern(null, patternNames(pattern));
    }

    private static List<Pattern> formalParameterList(FormalParameterListContext ctx) {
        List<Pattern> out = new ArrayList<>();
        if (ctx == null) {
            return out;
        }
        for (FormalParameterArgContext arg : ctx.formalParameterArg()) {
            AssignableContext a = arg.assignable();
            if (a.identifier() != null || a.keyword() != null) {
                out.add(new Pattern(a.getText(), List.of(a.getText())));
            } else {
                Node pattern = a.arrayLiteral() != null ? arrayLiteral(a.arrayLiteral()) : objectLiteral(a.objectLiteral());
                out.add(new Pattern(null, patternNames(pattern)));
            }
        }
        if (ctx.lastFormalParameterArg() != null) {
            String name = ctx.lastFormalParameterArg().identifier().getText();
            out.add(new Pattern(name, List.of(name)));
        }
        if (ctx.arrayLiteral() != null) {
            out.add(new Pattern(null, patternNames(arrayLiteral(ctx.arrayLiteral()))));
        }
        if (ctx.objectLiteral() != null) {
            out.add(new Pattern(null, patternNames(objectLiteral(ctx.objectLiteral()))));
        }
        if (ctx.formalParameterList() != null) {
            out.addAll(formalParameterList(ctx.formalParameterList()));
        }
        return out;
    }

    private static Pattern patternOf(Node node) {
        if (node instanceof Ident i) {
            return new Pattern(i.name(), List.of(i.name()));
        }
        return new Pattern(null, patternNames(node));
    }

    /** Yapı bozma kalıbının bağladığı adlar: {@code { a, b: c, d = 1, ...e }} → a, c, d, e. */
    static List<String> patternNames(Node pattern) {
        List<String> names = new ArrayList<>();
        collectPatternNames(pattern, names);
        return names;
    }

    private static void collectPatternNames(Node node, List<String> names) {
        if (node instanceof Ident i) {
            names.add(i.name());
        } else if (node instanceof Obj o) {
            o.props().forEach(p -> collectPatternNames(p.value(), names));
        } else if (node instanceof Arr a) {
            a.elements().forEach(e -> collectPatternNames(e, names));
        } else if (node instanceof Spread s) {
            collectPatternNames(s.expr(), names);
        } else if (node instanceof Binary b && b.op().equals("=")) {
            collectPatternNames(b.left(), names);
        }
    }

    private static ClassNode classNode(String name, ClassHeritageContext heritage, ClassTailContext tail, ParserRuleContext ctx) {
        Node superClass = null;
        if (heritage != null && heritage.classExtendsClause() != null) {
            superClass = typeNameExpr(heritage.classExtendsClause().typeReference().typeName().getText(),
                    line(heritage));
        }
        List<Node> members = new ArrayList<>();
        for (ClassElementContext element : tail.classElement()) {
            if (element.constructorDeclaration() != null) {
                ConstructorDeclarationContext c = element.constructorDeclaration();
                members.add(new Func("constructor", formalParameterList(c.formalParameterList()),
                        functionBody(c.functionBody()), false, line(c), endLine(c)));
            } else if (element.propertyMemberDeclaration() instanceof MemberDeclarationExpressionContext m
                    && m.propertyName() != null) {
                String key = propertyName(m.propertyName());
                if (m.methodTail() != null && m.methodTail().OpenBrace() != null) { // gövdesiz imzalar atılır
                    members.add(new Func(key, parameterList(m.methodTail().callSignature().parameterList()),
                            functionBody(m.methodTail().functionBody()), isAsync(m.propertyMemberBase()), line(m),
                            endLine(m)));
                } else if (m.fieldTail() != null && m.fieldTail().initializer() != null) {
                    members.add(new VarDecl(new Pattern(key, List.of(key)),
                            expr(m.fieldTail().initializer().singleExpression()), line(m), endLine(m)));
                }
            } else if (element.propertyMemberDeclaration() != null || element.statement() != null) {
                members.add(generic(element));
            }
        }
        return new ClassNode(name, superClass, members, line(ctx), endLine(ctx));
    }

    private static boolean isAsync(PropertyMemberBaseContext base) {
        return base.memberModifier().stream().anyMatch(m -> m.Async() != null);
    }

    private static Node typeNameExpr(String dotted, int line) {
        String[] parts = dotted.split("\\.");
        Node node = new Ident(parts[0], line);
        for (int i = 1; i < parts.length; i++) {
            node = new Member(node, parts[i], line);
        }
        return node;
    }

    // ---- ifadeler ----

    private static Node sequence(ExpressionSequenceContext ctx) {
        List<SingleExpressionContext> list = ctx.singleExpression();
        if (list.size() == 1) {
            return expr(list.get(0));
        }
        return new Other(list.stream().map(JsLowering::expr).toList(), line(ctx));
    }

    static Node expr(SingleExpressionContext ctx) {
        try {
            return lowerExpr(ctx);
        } catch (RuntimeException e) {
            return recover(ctx, e);
        }
    }

    private static Node lowerExpr(SingleExpressionContext ctx) {
        int line = line(ctx);
        if (ctx instanceof IdentifierExpressionContext c) {
            return new Ident(c.identifierName().getText(), line);
        }
        if (ctx instanceof MemberDotExpressionContext c) {
            return new Member(expr(c.singleExpression()), c.identifierName().getText(), line);
        }
        if (ctx instanceof OptionalChainExpressionContext c) {
            return new Member(expr(c.singleExpression()), c.identifierName().getText(), line);
        }
        if (ctx instanceof ArgumentsExpressionContext c) {
            return new Call(expr(c.singleExpression()), arguments(c.arguments()), line);
        }
        if (ctx instanceof MemberIndexExpressionContext c) {
            return new Index(expr(c.singleExpression()), sequence(c.expressionSequence()), line);
        }
        if (ctx instanceof NewExpressionContext c) {
            return new New(newCallee(c.newCallee()), c.arguments() == null ? List.of() : arguments(c.arguments()), line);
        }
        if (ctx instanceof LiteralExpressionContext c) {
            return literal(c.literal());
        }
        if (ctx instanceof ThisExpressionContext) {
            return new Ident("this", line);
        }
        if (ctx instanceof SuperExpressionContext) {
            return new Ident("super", line);
        }
        if (ctx instanceof FunctionExpressionContext c) {
            return anonymousFunction(c.anonymousFunction());
        }
        if (ctx instanceof ClassExpressionContext c) {
            return classNode(c.identifier() == null ? null : c.identifier().getText(), c.classHeritage(), c.classTail(), c);
        }
        if (ctx instanceof ObjectLiteralExpressionContext c) {
            return objectLiteral(c.objectLiteral());
        }
        if (ctx instanceof ArrayLiteralExpressionContext c) {
            return arrayLiteral(c.arrayLiteral());
        }
        if (ctx instanceof JsxElementExpressionContext c) {
            return jsx(c.jsxElement());
        }
        if (ctx instanceof ParenthesizedExpressionContext c) {
            return sequence(c.expressionSequence());
        }
        if (ctx instanceof CastAsExpressionContext c) {
            return expr(c.singleExpression());
        }
        if (ctx instanceof SatisfiesExpressionContext c) {
            return expr(c.singleExpression());
        }
        if (ctx instanceof NonNullAssertionExpressionContext c) {
            return expr(c.singleExpression());
        }
        if (ctx instanceof TernaryExpressionContext c) {
            return new Cond(expr(c.singleExpression(0)), expr(c.singleExpression(1)), expr(c.singleExpression(2)), line);
        }
        if (ctx instanceof TemplateStringExpressionContext c) { // etiketli template: tag`...`
            return new Call(expr(c.singleExpression()), List.of(template(c.templateStringLiteral())), line);
        }
        if (ctx instanceof AwaitExpressionContext c) {
            return new Unary("await", expr(c.singleExpression()), line);
        }
        List<SingleExpressionContext> operands = ctx.getRuleContexts(SingleExpressionContext.class);
        if (operands.size() == 2) {
            return new Binary(operatorBetween(ctx, operands.get(0), operands.get(1)), expr(operands.get(0)),
                    expr(operands.get(1)), line);
        }
        if (operands.size() == 1 && ctx.getChildCount() == 2) {
            ParseTree first = ctx.getChild(0);
            String op = first instanceof TerminalNode t ? t.getText() : ctx.getChild(1).getText();
            return new Unary(op, expr(operands.get(0)), line);
        }
        return generic(ctx);
    }

    private static String operatorBetween(ParserRuleContext ctx, ParserRuleContext left, ParserRuleContext right) {
        StringBuilder op = new StringBuilder();
        boolean between = false;
        for (ParseTree child : ctx.children) {
            if (child == left) {
                between = true;
            } else if (child == right) {
                break;
            } else if (between) {
                op.append(child.getText());
            }
        }
        return op.toString();
    }

    private static Node newCallee(NewCalleeContext ctx) {
        Node node;
        int line = line(ctx);
        if (ctx.This() != null) {
            node = new Ident("this", line);
        } else if (ctx.singleExpression() != null) {
            node = expr(ctx.singleExpression());
        } else if (ctx.newCallee() != null) {
            node = new New(newCallee(ctx.newCallee()), List.of(), line);
        } else {
            node = new Ident(ctx.identifierName(0).getText(), line);
        }
        int nameIndex = ctx.This() == null && ctx.singleExpression() == null && ctx.newCallee() == null ? 1 : 0;
        int indexIndex = 0;
        for (ParseTree child : ctx.children) {
            if (child instanceof TerminalNode t && t.getSymbol().getType() == TsxParser.Dot) {
                node = new Member(node, ctx.identifierName(nameIndex++).getText(), line);
            } else if (child instanceof TerminalNode t && t.getSymbol().getType() == TsxParser.OpenBracket) {
                node = new Index(node, sequence(ctx.expressionSequence(indexIndex++)), line);
            }
        }
        return node;
    }

    private static List<Node> arguments(ArgumentsContext ctx) {
        List<Node> out = new ArrayList<>();
        if (ctx.argumentList() != null) {
            for (ArgumentContext arg : ctx.argumentList().argument()) {
                Node value = arg.singleExpression() != null
                        ? expr(arg.singleExpression())
                        : new Ident(arg.identifier().getText(), line(arg));
                out.add(arg.Ellipsis() != null ? new Spread(value, line(arg)) : value);
            }
        }
        return out;
    }

    private static Node literal(LiteralContext ctx) {
        int line = line(ctx);
        if (ctx.StringLiteral() != null) {
            return new Str(unquote(ctx.StringLiteral().getText()), line);
        }
        if (ctx.templateStringLiteral() != null) {
            return template(ctx.templateStringLiteral());
        }
        return new Literal(ctx.getText(), line);
    }

    private static Node template(TemplateStringLiteralContext ctx) {
        List<String> quasis = new ArrayList<>();
        List<Node> exprs = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (TemplateStringAtomContext atom : ctx.templateStringAtom()) {
            if (atom.singleExpression() != null) {
                quasis.add(current.toString());
                current.setLength(0);
                exprs.add(expr(atom.singleExpression()));
            } else if (atom.TemplateStringEscapeAtom() != null) {
                current.append(unescape(atom.getText()));
            } else {
                current.append(atom.getText());
            }
        }
        quasis.add(current.toString());
        if (exprs.isEmpty()) {
            return new Str(quasis.get(0), line(ctx));
        }
        return new Template(quasis, exprs, line(ctx));
    }

    private static Obj objectLiteral(ObjectLiteralContext ctx) {
        List<Prop> props = new ArrayList<>();
        for (PropertyAssignmentContext p : ctx.propertyAssignment()) {
            if (p instanceof PropertyExpressionAssignmentContext c) {
                String key = propertyName(c.propertyName());
                Node value = c.Assign() != null // kalıptaki varsayılan değer: { a = 1 }
                        ? new Binary("=", new Ident(key, line(c)), expr(c.singleExpression()), line(c))
                        : expr(c.singleExpression());
                props.add(new Prop(key, value, false));
            } else if (p instanceof PropertyShorthandContext c) {
                props.add(new Prop(c.getText(), new Ident(c.getText(), line(c)), false));
            } else if (p instanceof SpreadOperatorContext c) {
                Node value = expr(c.singleExpression());
                if (c.Ellipsis() != null) {
                    props.add(new Prop(null, new Spread(value, line(c)), true));
                } else {
                    String key = value instanceof Ident i ? i.name() : null;
                    props.add(new Prop(key, value, false));
                }
            } else if (p instanceof MethodPropertyContext c) {
                GeneratorMethodContext m = c.generatorMethod();
                String key = propertyName(m.propertyName());
                props.add(new Prop(key, new Func(key, formalParameterList(m.formalParameterList()),
                        functionBody(m.functionBody()), m.Async() != null, line(m), endLine(m)), false));
            } else if (p instanceof ComputedPropertyExpressionAssignmentContext c) {
                props.add(new Prop(null, new Other(List.of(expr(c.singleExpression(0)), expr(c.singleExpression(1))),
                        line(c)), false));
            } else {
                props.add(new Prop(null, generic(p), false));
            }
        }
        return new Obj(props, line(ctx));
    }

    private static String propertyName(PropertyNameContext ctx) {
        if (ctx.StringLiteral() != null) {
            return unquote(ctx.StringLiteral().getText());
        }
        if (ctx.privateIdentifier() != null) { // #alan; erişimi this.alan olarak indirgenir
            return ctx.privateIdentifier().identifierName().getText();
        }
        if (ctx.singleExpression() != null) {
            return null;
        }
        return ctx.getText();
    }

    private static Arr arrayLiteral(ArrayLiteralContext ctx) {
        List<Node> elements = new ArrayList<>();
        if (ctx.elementList() != null) {
            for (ArrayElementContext e : ctx.elementList().arrayElement()) {
                Node value = e.singleExpression() != null
                        ? expr(e.singleExpression())
                        : new Ident(e.identifier().getText(), line(e));
                elements.add(e.Ellipsis() != null ? new Spread(value, line(e)) : value);
            }
        }
        return new Arr(elements, line(ctx));
    }

    // ---- JSX ----

    private static Node jsx(JsxElementContext ctx) {
        try {
            return lowerJsx(ctx);
        } catch (RuntimeException e) {
            return recover(ctx, e);
        }
    }

    private static Jsx lowerJsx(JsxElementContext ctx) {
        if (ctx.jsxSelfClosingElement() != null) {
            JsxSelfClosingElementContext e = ctx.jsxSelfClosingElement();
            return new Jsx(text(e.JsxOpeningElementId()), attributes(e.jsxAttribute()), List.of(), line(ctx));
        }
        JsxOpeningElementContext open = ctx.jsxOpeningElement();
        List<Node> children = new ArrayList<>();
        for (JsxChildContext child : ctx.jsxChild()) {
            if (child.jsxElement() != null) {
                children.add(jsx(child.jsxElement()));
            } else if (child.jsxExpressionContainer() != null) {
                Node value = expressionContainer(child.jsxExpressionContainer());
                if (value != null) {
                    children.add(value);
                }
            }
        }
        return new Jsx(text(open.JsxOpeningElementId()), attributes(open.jsxAttribute()), children, line(ctx));
    }

    private static List<JsxAttr> attributes(List<JsxAttributeContext> attrs) {
        List<JsxAttr> out = new ArrayList<>();
        for (JsxAttributeContext a : attrs) {
            if (a instanceof JsxSpreadAttributeContext s) {
                out.add(new JsxAttr(null, expr(s.singleExpression())));
            } else if (a instanceof JsxNamedAttributeContext n) {
                Node value = null;
                JsxAttributeValueContext v = n.jsxAttributeValue();
                if (v != null) {
                    if (v.JsxAttributeValue() != null) {
                        value = new Str(unquote(v.JsxAttributeValue().getText()), line(v));
                    } else if (v.jsxElement() != null) {
                        value = jsx(v.jsxElement());
                    } else {
                        value = expressionContainer(v.jsxExpressionContainer());
                    }
                }
                out.add(new JsxAttr(n.JsxOpeningElementId().getText(), value));
            }
        }
        return out;
    }

    private static Node expressionContainer(JsxExpressionContainerContext ctx) {
        return ctx.expressionSequence() == null ? null : sequence(ctx.expressionSequence());
    }

    // ---- genel ----

    private static boolean isTypeContext(ParseTree tree) {
        return tree instanceof Type_Context
                || tree instanceof TypeAnnotationContext
                || tree instanceof TypeArgumentsContext
                || tree instanceof TypeParametersContext
                || tree instanceof InterfaceDeclarationContext
                || tree instanceof TypeAliasDeclarationContext
                || tree instanceof ImplementsClauseContext;
    }

    /** Tanınmayan yapı: çocukları indirgenip {@link Other} içinde korunur. */
    private static Node generic(ParserRuleContext ctx) {
        List<Node> children = new ArrayList<>();
        collect(ctx, children);
        return new Other(children, line(ctx));
    }

    private static void collect(ParserRuleContext ctx, List<Node> out) {
        if (ctx.children == null) {
            return;
        }
        for (ParseTree child : ctx.children) {
            if (!(child instanceof ParserRuleContext rule) || isTypeContext(child)) {
                continue;
            }
            try {
                collectChild(rule, out);
            } catch (RuntimeException e) {
                if (!recovered(rule)) {
                    throw e;
                }
                collect(rule, out);
            }
        }
    }

    private static void collectChild(ParserRuleContext rule, List<Node> out) {
        if (rule instanceof SingleExpressionContext e) {
            out.add(expr(e));
        } else if (rule instanceof StatementContext s) {
            out.addAll(statement(s));
        } else if (rule instanceof ExpressionSequenceContext s) {
            out.add(sequence(s));
        } else if (rule instanceof FunctionBodyContext b) {
            out.addAll(functionBody(b));
        } else if (rule instanceof AnonymousFunctionContext f) {
            out.add(anonymousFunction(f));
        } else if (rule instanceof ArrowFunctionDeclarationContext f) {
            out.add(arrow(f));
        } else if (rule instanceof FunctionDeclarationContext f) {
            out.add(functionDeclaration(f));
        } else if (rule instanceof VariableStatementContext v) {
            out.addAll(variableStatement(v));
        } else if (rule instanceof ObjectLiteralContext o) {
            out.add(objectLiteral(o));
        } else if (rule instanceof ArrayLiteralContext a) {
            out.add(arrayLiteral(a));
        } else if (rule instanceof JsxElementContext j) {
            out.add(jsx(j));
        } else {
            collect(rule, out);
        }
    }

    /** Hata toparlamasıyla eksik kalmış alt ağaç: indirgenebilen çocukları korunur. */
    private static Node recover(ParserRuleContext ctx, RuntimeException e) {
        if (!recovered(ctx)) {
            throw e;
        }
        return generic(ctx);
    }

    /** Alt ağaçta ANTLR'ın hata toparlamasının izi (yakalanan istisna ya da hata düğümü) var mı? */
    private static boolean recovered(ParserRuleContext ctx) {
        if (ctx.exception != null) {
            return true;
        }
        if (ctx.children != null) {
            for (ParseTree child : ctx.children) {
                if (child instanceof ErrorNode
                        || child instanceof ParserRuleContext rule && recovered(rule)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static String text(TerminalNode node) {
        return node == null ? null : node.getText();
    }

    static int line(ParserRuleContext ctx) {
        return ctx.getStart().getLine();
    }

    static int endLine(ParserRuleContext ctx) {
        Token stop = ctx.getStop();
        return stop == null ? line(ctx) : stop.getLine();
    }

    /** Tırnaklı metin literalini çözer: {@code 'a\'b'} → {@code a'b}. */
    static String unquote(String literal) {
        if (literal.length() >= 2) {
            char q = literal.charAt(0);
            if ((q == '\'' || q == '"' || q == '`') && literal.charAt(literal.length() - 1) == q) {
                return unescape(literal.substring(1, literal.length() - 1));
            }
        }
        return literal;
    }

    private static String unescape(String s) {
        if (s.indexOf('\\') < 0) {
            return s;
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c != '\\' || i + 1 >= s.length()) {
                sb.append(c);
                continue;
            }
            char n = s.charAt(++i);
            switch (n) {
                case 'n' -> sb.append('\n');
                case 't' -> sb.append('\t');
                case 'r' -> sb.append('\r');
                case '\n' -> { } // satır devamı
                case 'u' -> {
                    if (i + 4 < s.length() && s.charAt(i + 1) != '{') {
                        try {
                            sb.append((char) Integer.parseInt(s.substring(i + 1, i + 5), 16));
                            i += 4;
                        } catch (NumberFormatException e) {
                            sb.append(n);
                        }
                    } else {
                        sb.append(n);
                    }
                }
                default -> sb.append(n);
            }
        }
        return sb.toString();
    }
}
