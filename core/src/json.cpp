#include "json.hpp"

#include <charconv>
#include <cmath>
#include <cstdlib>
#include <limits>
#include <sstream>

namespace phytoy::json {

namespace {

[[noreturn]] void type_error(const char* expected) {
    throw std::runtime_error(std::string("JSON value is not ") + expected);
}

class Parser {
public:
    explicit Parser(std::string_view source) : source_(source) {}

    Value parse_document() {
        skip_space();
        Value result = parse_value();
        skip_space();
        if (position_ != source_.size()) {
            fail("trailing data");
        }
        return result;
    }

private:
    [[noreturn]] void fail(const char* message) const {
        std::ostringstream stream;
        stream << "JSON parse error at byte " << position_ << ": " << message;
        throw std::runtime_error(stream.str());
    }

    void skip_space() {
        while (position_ < source_.size()) {
            const char c = source_[position_];
            if (c != ' ' && c != '\n' && c != '\r' && c != '\t') {
                break;
            }
            ++position_;
        }
    }

    bool consume(char expected) {
        if (position_ < source_.size() && source_[position_] == expected) {
            ++position_;
            return true;
        }
        return false;
    }

    Value parse_value() {
        skip_space();
        if (position_ >= source_.size()) {
            fail("unexpected end of input");
        }
        switch (source_[position_]) {
            case 'n': return parse_literal("null", Value::Storage{nullptr});
            case 't': return parse_literal("true", Value::Storage{true});
            case 'f': return parse_literal("false", Value::Storage{false});
            case '"': return Value(Value::Storage{parse_string()});
            case '[': return parse_array();
            case '{': return parse_object();
            default:
                if (source_[position_] == '-' ||
                    (source_[position_] >= '0' && source_[position_] <= '9')) {
                    return parse_number();
                }
                fail("unexpected token");
        }
    }

    Value parse_literal(std::string_view literal, Value::Storage value) {
        if (source_.substr(position_, literal.size()) != literal) {
            fail("invalid literal");
        }
        position_ += literal.size();
        return Value(std::move(value));
    }

    std::string parse_string() {
        if (!consume('"')) {
            fail("expected string");
        }
        std::string result;
        while (position_ < source_.size()) {
            char c = source_[position_++];
            if (c == '"') {
                return result;
            }
            if (static_cast<unsigned char>(c) < 0x20U) {
                fail("control character in string");
            }
            if (c != '\\') {
                result.push_back(c);
                continue;
            }
            if (position_ >= source_.size()) {
                fail("unterminated escape");
            }
            const char escaped = source_[position_++];
            switch (escaped) {
                case '"': result.push_back('"'); break;
                case '\\': result.push_back('\\'); break;
                case '/': result.push_back('/'); break;
                case 'b': result.push_back('\b'); break;
                case 'f': result.push_back('\f'); break;
                case 'n': result.push_back('\n'); break;
                case 'r': result.push_back('\r'); break;
                case 't': result.push_back('\t'); break;
                case 'u': {
                    if (position_ + 4 > source_.size()) {
                        fail("short unicode escape");
                    }
                    unsigned codepoint = 0;
                    for (int i = 0; i < 4; ++i) {
                        const char hex = source_[position_++];
                        codepoint <<= 4U;
                        if (hex >= '0' && hex <= '9') codepoint += static_cast<unsigned>(hex - '0');
                        else if (hex >= 'a' && hex <= 'f') codepoint += static_cast<unsigned>(hex - 'a' + 10);
                        else if (hex >= 'A' && hex <= 'F') codepoint += static_cast<unsigned>(hex - 'A' + 10);
                        else fail("invalid unicode escape");
                    }
                    if (codepoint <= 0x7FU) result.push_back(static_cast<char>(codepoint));
                    else if (codepoint <= 0x7FFU) {
                        result.push_back(static_cast<char>(0xC0U | (codepoint >> 6U)));
                        result.push_back(static_cast<char>(0x80U | (codepoint & 0x3FU)));
                    } else {
                        result.push_back(static_cast<char>(0xE0U | (codepoint >> 12U)));
                        result.push_back(static_cast<char>(0x80U | ((codepoint >> 6U) & 0x3FU)));
                        result.push_back(static_cast<char>(0x80U | (codepoint & 0x3FU)));
                    }
                    break;
                }
                default: fail("invalid escape");
            }
        }
        fail("unterminated string");
    }

    Value parse_number() {
        const size_t start = position_;
        if (consume('-') && position_ >= source_.size()) fail("short number");
        if (consume('0')) {
            // A leading zero is complete unless followed by a fraction or exponent.
        } else {
            if (position_ >= source_.size() || source_[position_] < '1' || source_[position_] > '9') {
                fail("invalid number");
            }
            while (position_ < source_.size() && source_[position_] >= '0' && source_[position_] <= '9') {
                ++position_;
            }
        }
        if (consume('.')) {
            const size_t fraction = position_;
            while (position_ < source_.size() && source_[position_] >= '0' && source_[position_] <= '9') {
                ++position_;
            }
            if (fraction == position_) fail("empty fraction");
        }
        if (position_ < source_.size() && (source_[position_] == 'e' || source_[position_] == 'E')) {
            ++position_;
            if (position_ < source_.size() && (source_[position_] == '+' || source_[position_] == '-')) ++position_;
            const size_t exponent = position_;
            while (position_ < source_.size() && source_[position_] >= '0' && source_[position_] <= '9') {
                ++position_;
            }
            if (exponent == position_) fail("empty exponent");
        }
        const std::string token(source_.substr(start, position_ - start));
        char* end = nullptr;
        const double number = std::strtod(token.c_str(), &end);
        if (end != token.c_str() + token.size() || !std::isfinite(number)) {
            fail("invalid or non-finite number");
        }
        return Value(Value::Storage{number});
    }

    Value parse_array() {
        consume('[');
        Value::Array result;
        skip_space();
        if (consume(']')) return Value(Value::Storage{std::move(result)});
        while (true) {
            result.push_back(parse_value());
            skip_space();
            if (consume(']')) break;
            if (!consume(',')) fail("expected ',' in array");
        }
        return Value(Value::Storage{std::move(result)});
    }

    Value parse_object() {
        consume('{');
        Value::Object result;
        skip_space();
        if (consume('}')) return Value(Value::Storage{std::move(result)});
        while (true) {
            skip_space();
            std::string key = parse_string();
            skip_space();
            if (!consume(':')) fail("expected ':' in object");
            Value value = parse_value();
            if (!result.emplace(std::move(key), std::move(value)).second) {
                fail("duplicate object key");
            }
            skip_space();
            if (consume('}')) break;
            if (!consume(',')) fail("expected ',' in object");
        }
        return Value(Value::Storage{std::move(result)});
    }

    std::string_view source_;
    size_t position_{};
};

}  // namespace

bool Value::is_null() const { return std::holds_alternative<std::nullptr_t>(storage_); }
bool Value::is_bool() const { return std::holds_alternative<bool>(storage_); }
bool Value::is_number() const { return std::holds_alternative<double>(storage_); }
bool Value::is_string() const { return std::holds_alternative<std::string>(storage_); }
bool Value::is_array() const { return std::holds_alternative<Array>(storage_); }
bool Value::is_object() const { return std::holds_alternative<Object>(storage_); }
bool Value::as_bool() const { if (!is_bool()) type_error("a boolean"); return std::get<bool>(storage_); }
double Value::as_number() const { if (!is_number()) type_error("a number"); return std::get<double>(storage_); }
const std::string& Value::as_string() const { if (!is_string()) type_error("a string"); return std::get<std::string>(storage_); }
const Value::Array& Value::as_array() const { if (!is_array()) type_error("an array"); return std::get<Array>(storage_); }
const Value::Object& Value::as_object() const { if (!is_object()) type_error("an object"); return std::get<Object>(storage_); }

const Value& Value::at(std::string_view key) const {
    const auto& object = as_object();
    const auto iterator = object.find(key);
    if (iterator == object.end()) {
        throw std::runtime_error("missing JSON field: " + std::string(key));
    }
    return iterator->second;
}

const Value* Value::find(std::string_view key) const {
    const auto& object = as_object();
    const auto iterator = object.find(key);
    return iterator == object.end() ? nullptr : &iterator->second;
}

Value parse(std::string_view source) {
    return Parser(source).parse_document();
}

}  // namespace phytoy::json

