package dev.advancedtrains.atcs.fare;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;
import java.util.Set;

/** A small arithmetic language, not executable code. Missing price components are skipped by min/max. */
public final class FareExpression {
    public static final String ADDITIVE = "base + stops + changes + distance + zones + pairs";
    public static final Set<String> COMPONENTS = Set.of("base", "stops", "changes", "distance", "zones", "pairs", "formula");
    private final Node root;

    private FareExpression(Node root) {
        this.root = root;
    }

    public static FareExpression compile(String expression) {
        if (expression == null || expression.isBlank() || expression.length() > 512) {
            throw new IllegalArgumentException("calculation: give an expression of 1–512 characters");
        }
        Parser parser = new Parser(expression);
        Node root = parser.expression(0);
        parser.space();
        if (parser.at != expression.length()) {
            throw parser.error("unexpected character");
        }
        return new FareExpression(root);
    }

    /** Missing terms are absent, not zero-priced alternatives. An explicitly configured zero is present. */
    public double evaluate(Map<String, Double> components) {
        double result = root.value(components).orElse(0);
        finite(result);
        return Math.max(0, result);
    }

    private interface Node {
        OptionalDouble value(Map<String, Double> components);
    }

    private record Number(double number) implements Node {
        @Override
        public OptionalDouble value(Map<String, Double> components) {
            return OptionalDouble.of(number);
        }
    }

    private record Term(String name) implements Node {
        @Override
        public OptionalDouble value(Map<String, Double> components) {
            Double value = components.get(name);
            return value == null ? OptionalDouble.empty() : OptionalDouble.of(value);
        }
    }

    private record Arithmetic(char operator, Node left, Node right) implements Node {
        @Override
        public OptionalDouble value(Map<String, Double> components) {
            OptionalDouble a = left.value(components), b = right.value(components);
            if (a.isEmpty() && b.isEmpty() || (operator == '*' || operator == '/') && (a.isEmpty() || b.isEmpty())) {
                return OptionalDouble.empty();
            }
            double value = switch (operator) {
                case '+' -> a.orElse(0) + b.orElse(0);
                case '-' -> a.orElse(0) - b.orElse(0);
                case '*' -> a.orElse(0) * b.orElse(0);
                case '/' -> a.orElse(0) / b.orElseThrow();
                default -> throw new IllegalStateException("Unknown operator");
            };
            finite(value);
            return OptionalDouble.of(value);
        }
    }

    private record Function(String name, List<Node> children) implements Node {
        @Override
        public OptionalDouble value(Map<String, Double> components) {
            OptionalDouble result = OptionalDouble.empty();
            for (Node child : children) {
                OptionalDouble value = child.value(components);
                if (value.isEmpty()) {
                    continue;
                }
                double next = result.isEmpty() ? value.getAsDouble() : switch (name) {
                    case "min" -> Math.min(result.getAsDouble(), value.getAsDouble());
                    case "max" -> Math.max(result.getAsDouble(), value.getAsDouble());
                    default -> result.getAsDouble() + value.getAsDouble();
                };
                finite(next);
                result = OptionalDouble.of(next);
            }
            return result;
        }
    }

    private static void finite(double value) {
        if (!Double.isFinite(value)) {
            throw new IllegalArgumentException("calculation: result is not a finite amount");
        }
    }

    private static final class Parser {
        private final String text;
        private int at;
        private int nodes;

        Parser(String text) {
            this.text = text;
        }

        Node expression(int depth) {
            Node left = product(depth);
            while (true) {
                space();
                if (at == text.length() || text.charAt(at) != '+' && text.charAt(at) != '-') {
                    return left;
                }
                char operator = text.charAt(at++);
                left = node(new Arithmetic(operator, left, product(depth)));
            }
        }

        Node product(int depth) {
            Node left = primary(depth);
            while (true) {
                space();
                if (at == text.length() || text.charAt(at) != '*' && text.charAt(at) != '/') {
                    return left;
                }
                char operator = text.charAt(at++);
                Node right = primary(depth);
                if (operator == '/' && (!(right instanceof Number number) || number.number() <= 0)) {
                    throw error("divide only by a positive constant");
                }
                left = node(new Arithmetic(operator, left, right));
            }
        }

        Node primary(int depth) {
            if (depth > 24) {
                throw error("too many nested groups");
            }
            space();
            if (take('(')) {
                Node inside = expression(depth + 1);
                require(')');
                return inside;
            }
            if (at < text.length() && (Character.isDigit(text.charAt(at)) || text.charAt(at) == '.')) {
                int start = at;
                while (at < text.length() && (Character.isDigit(text.charAt(at)) || text.charAt(at) == '.')) {
                    at++;
                }
                try {
                    double number = Double.parseDouble(text.substring(start, at));
                    finite(number);
                    return node(new Number(number));
                } catch (NumberFormatException exception) {
                    throw error("invalid number");
                }
            }
            int start = at;
            while (at < text.length() && Character.isLetter(text.charAt(at))) {
                at++;
            }
            String name = text.substring(start, at);
            if (COMPONENTS.contains(name)) {
                return node(new Term(name));
            }
            if (!Set.of("sum", "min", "max").contains(name)) {
                throw error("expected a price component, number, sum(...), min(...) or max(...)");
            }
            require('(');
            List<Node> children = new ArrayList<>();
            children.add(expression(depth + 1));
            while (take(',')) {
                children.add(expression(depth + 1));
            }
            require(')');
            return node(new Function(name, List.copyOf(children)));
        }

        Node node(Node node) {
            if (++nodes > 128) {
                throw error("too many terms");
            }
            return node;
        }

        void space() {
            while (at < text.length() && Character.isWhitespace(text.charAt(at))) {
                at++;
            }
        }

        boolean take(char character) {
            space();
            if (at < text.length() && text.charAt(at) == character) {
                at++;
                return true;
            }
            return false;
        }

        void require(char character) {
            if (!take(character)) {
                throw error("expected '" + character + "'");
            }
        }

        IllegalArgumentException error(String message) {
            return new IllegalArgumentException("calculation at character " + (at + 1) + ": " + message);
        }
    }
}
