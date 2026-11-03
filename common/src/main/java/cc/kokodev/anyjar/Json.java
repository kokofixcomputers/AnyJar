package cc.kokodev.anyjar;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Minimal recursive-descent JSON reader/writer, just enough to walk mcjars.app's API
 * responses and round-trip a chosen build's install steps to disk, without pulling in an
 * extra dependency.
 */
final class Json {
	private final String src;
	private int pos;

	private Json(String src) {
		this.src = src;
	}

	static Object parse(String text) {
		Json parser = new Json(text);
		parser.skipWhitespace();
		Object value = parser.parseValue();
		parser.skipWhitespace();
		return value;
	}

	static String write(Object value) {
		StringBuilder sb = new StringBuilder();
		writeValue(value, sb);
		return sb.toString();
	}

	@SuppressWarnings("unchecked")
	private static void writeValue(Object value, StringBuilder sb) {
		if (value == null) {
			sb.append("null");
		} else if (value instanceof String s) {
			writeString(s, sb);
		} else if (value instanceof Boolean || value instanceof Number) {
			sb.append(value);
		} else if (value instanceof Map<?, ?> map) {
			sb.append('{');
			boolean first = true;
			for (Map.Entry<?, ?> entry : map.entrySet()) {
				if (!first) sb.append(',');
				first = false;
				writeString(String.valueOf(entry.getKey()), sb);
				sb.append(':');
				writeValue(entry.getValue(), sb);
			}
			sb.append('}');
		} else if (value instanceof List<?> list) {
			sb.append('[');
			boolean first = true;
			for (Object item : list) {
				if (!first) sb.append(',');
				first = false;
				writeValue(item, sb);
			}
			sb.append(']');
		} else {
			throw new IllegalArgumentException("Cannot serialize " + value.getClass());
		}
	}

	private static void writeString(String s, StringBuilder sb) {
		sb.append('"');
		for (int i = 0; i < s.length(); i++) {
			char c = s.charAt(i);
			switch (c) {
				case '"': sb.append("\\\""); break;
				case '\\': sb.append("\\\\"); break;
				case '\n': sb.append("\\n"); break;
				case '\r': sb.append("\\r"); break;
				case '\t': sb.append("\\t"); break;
				default:
					if (c < 0x20) {
						sb.append(String.format("\\u%04x", (int) c));
					} else {
						sb.append(c);
					}
			}
		}
		sb.append('"');
	}

	private Object parseValue() {
		switch (peek()) {
			case '{': return parseObject();
			case '[': return parseArray();
			case '"': return parseString();
			case 't': expect("true"); return Boolean.TRUE;
			case 'f': expect("false"); return Boolean.FALSE;
			case 'n': expect("null"); return null;
			default: return parseNumber();
		}
	}

	private Map<String, Object> parseObject() {
		Map<String, Object> map = new LinkedHashMap<>();
		expectChar('{');
		skipWhitespace();
		if (peek() == '}') {
			pos++;
			return map;
		}
		while (true) {
			skipWhitespace();
			String key = parseString();
			skipWhitespace();
			expectChar(':');
			skipWhitespace();
			map.put(key, parseValue());
			skipWhitespace();
			char c = next();
			if (c == '}') break;
			if (c != ',') throw new IllegalStateException("Expected ',' or '}' at " + pos);
		}
		return map;
	}

	private List<Object> parseArray() {
		List<Object> list = new ArrayList<>();
		expectChar('[');
		skipWhitespace();
		if (peek() == ']') {
			pos++;
			return list;
		}
		while (true) {
			skipWhitespace();
			list.add(parseValue());
			skipWhitespace();
			char c = next();
			if (c == ']') break;
			if (c != ',') throw new IllegalStateException("Expected ',' or ']' at " + pos);
		}
		return list;
	}

	private String parseString() {
		expectChar('"');
		StringBuilder sb = new StringBuilder();
		while (true) {
			char c = next();
			if (c == '"') break;
			if (c == '\\') {
				char esc = next();
				switch (esc) {
					case '"': sb.append('"'); break;
					case '\\': sb.append('\\'); break;
					case '/': sb.append('/'); break;
					case 'b': sb.append('\b'); break;
					case 'f': sb.append('\f'); break;
					case 'n': sb.append('\n'); break;
					case 'r': sb.append('\r'); break;
					case 't': sb.append('\t'); break;
					case 'u':
						String hex = src.substring(pos, pos + 4);
						pos += 4;
						sb.append((char) Integer.parseInt(hex, 16));
						break;
					default: throw new IllegalStateException("Unknown escape: \\" + esc);
				}
			} else {
				sb.append(c);
			}
		}
		return sb.toString();
	}

	private Object parseNumber() {
		int start = pos;
		if (peek() == '-') pos++;
		while (pos < src.length()) {
			char c = src.charAt(pos);
			if (Character.isDigit(c) || c == '.' || c == 'e' || c == 'E' || c == '+' || c == '-') {
				pos++;
			} else {
				break;
			}
		}
		String number = src.substring(start, pos);
		if (number.indexOf('.') >= 0 || number.indexOf('e') >= 0 || number.indexOf('E') >= 0) {
			return Double.parseDouble(number);
		}
		try {
			return Long.parseLong(number);
		} catch (NumberFormatException e) {
			return Double.parseDouble(number);
		}
	}

	private void expect(String literal) {
		if (!src.regionMatches(pos, literal, 0, literal.length())) {
			throw new IllegalStateException("Expected '" + literal + "' at " + pos);
		}
		pos += literal.length();
	}

	private void expectChar(char expected) {
		char c = next();
		if (c != expected) throw new IllegalStateException("Expected '" + expected + "' but got '" + c + "' at " + pos);
	}

	private char next() {
		return src.charAt(pos++);
	}

	private char peek() {
		return src.charAt(pos);
	}

	private void skipWhitespace() {
		while (pos < src.length() && Character.isWhitespace(src.charAt(pos))) pos++;
	}
}
