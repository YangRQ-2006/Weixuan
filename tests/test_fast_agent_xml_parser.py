import unittest

from mobilerun.agent.fast_agent.xml_parser import (
    ToolCallParseStatus,
    extract_add_memory,
    format_tool_calls,
    parse_tool_calls,
    parse_tool_calls_detailed,
)


class FastAgentXmlParserTest(unittest.TestCase):
    def test_classifies_plain_text_as_no_markup(self):
        result = parse_tool_calls_detailed("I need to inspect the current screen.")

        self.assertEqual(result.status, ToolCallParseStatus.NO_MARKUP)
        self.assertEqual(result.thought, "I need to inspect the current screen.")
        self.assertEqual(result.calls, [])

    def test_classifies_discord_dsml_fixture_as_malformed(self):
        text = """I'm still on the main feed. I need to tap on the Profile tab.

<function_calls>
<invoke name="click">
<｜DSML｜ name="index">189</｜DSML｜>
</invoke>
</function_calls>"""

        result = parse_tool_calls_detailed(text, {"index": "number"})

        self.assertEqual(result.status, ToolCallParseStatus.MALFORMED)
        self.assertIn("Profile tab", result.thought)
        self.assertEqual(result.calls, [])

    def test_classifies_captured_hybrid_dsml_as_malformed(self):
        text = """I will return to Settings home.
<function_calls>
<invoke name="system_button">
<｜｜DSML｜｜rameter name="button">back</｜｜DSML｜｜rameter>
</｜｜DSML｜｜invoke>
</function_calls>"""

        result = parse_tool_calls_detailed(text)

        self.assertEqual(result.status, ToolCallParseStatus.MALFORMED)
        self.assertEqual(result.calls, [])

    def test_rejects_dsml_corruption_inside_parameter_value(self):
        text = """Settings home is visible. Marking the task complete.
<function_calls>
<invoke name="complete">
<parameter name="success">true</｜｜DSML｜｜>
<parameter name="message">Completed 20 verified cycles.</parameter>
</invoke>
</function_calls>"""

        result = parse_tool_calls_detailed(text, {"success": "boolean"})

        self.assertEqual(result.status, ToolCallParseStatus.MALFORMED)
        self.assertEqual(result.calls, [])

    def test_rejects_dsml_corruption_crossing_xml_parameter_variant(self):
        text = """Settings home is visible. Marking the task complete.
<function_calls>
<invoke name="complete">
<parameter name="success">true</｜｜DSML｜｜>
<parameter name = "message">Completed 20 verified cycles.</parameter>
</invoke>
</function_calls>"""

        result = parse_tool_calls_detailed(text, {"success": "boolean"})

        self.assertEqual(result.status, ToolCallParseStatus.MALFORMED)
        self.assertEqual(result.calls, [])

    def test_allows_ordinary_dsml_text_inside_parameter_value(self):
        text = """I will type the literal markup.
<function_calls>
<invoke name="type_text">
<parameter name="text"><span title="DSML">literal payload</span></parameter>
</invoke>
</function_calls>"""

        result = parse_tool_calls_detailed(text)

        self.assertEqual(result.status, ToolCallParseStatus.VALID)
        self.assertEqual(len(result.calls), 1)
        self.assertEqual(
            result.calls[0].parameters,
            {"text": '<span title="DSML">literal payload</span>'},
        )

    def test_allows_literal_dsml_syntax_inside_parameter_value(self):
        text = """I will type the literal DSML syntax.
<function_calls>
<invoke name="type_text">
<parameter name="text">before <｜DSML｜tool_calls>literal</｜DSML｜tool_calls> after</parameter>
</invoke>
</function_calls>"""

        result = parse_tool_calls_detailed(text)

        self.assertEqual(result.status, ToolCallParseStatus.VALID)
        self.assertEqual(len(result.calls), 1)
        self.assertEqual(
            result.calls[0].parameters,
            {"text": "before <｜DSML｜tool_calls>literal</｜DSML｜tool_calls> after"},
        )
        thought, calls = parse_tool_calls(text)
        self.assertEqual(thought, result.thought)
        self.assertEqual(calls, result.calls)

    def test_literal_dsml_payload_does_not_hide_structural_dsml(self):
        text = """I will type text and then tap.
<function_calls>
<invoke name="type_text">
<parameter name="text">literal <｜DSML｜payload></parameter>
</invoke>
<｜DSML｜ name="click">
<parameter name="index">12</parameter>
</｜DSML｜>
</function_calls>"""

        result = parse_tool_calls_detailed(text, {"index": "number"})

        self.assertEqual(result.status, ToolCallParseStatus.MALFORMED)
        self.assertEqual(result.calls, [])

    def test_allows_unmatched_tool_like_text_inside_parameter_value(self):
        text = """I will type the literal snippet.
<function_calls>
<invoke name="type_text">
<parameter name="text">literal <invoke name="example"> token</parameter>
</invoke>
</function_calls>"""

        result = parse_tool_calls_detailed(text)

        self.assertEqual(result.status, ToolCallParseStatus.VALID)
        self.assertEqual(len(result.calls), 1)
        self.assertEqual(
            result.calls[0].parameters,
            {"text": 'literal <invoke name="example"> token'},
        )

    def test_classifies_dsml_without_xml_wrapper_as_malformed(self):
        text = """I will tap the target.
<｜DSML｜tool_calls>
<｜DSML｜invoke name="click">
<｜DSML｜parameter name="index">12</｜DSML｜parameter>
</｜DSML｜invoke>
</｜DSML｜tool_calls>"""

        result = parse_tool_calls_detailed(text, {"index": "number"})

        self.assertEqual(result.status, ToolCallParseStatus.MALFORMED)
        self.assertEqual(result.thought, "I will tap the target.")
        self.assertEqual(result.calls, [])

    def test_classifies_standalone_invoke_as_malformed(self):
        text = """I will tap the target.
<invoke name="click"><parameter name="index">12</parameter></invoke>"""

        result = parse_tool_calls_detailed(text, {"index": "number"})

        self.assertEqual(result.status, ToolCallParseStatus.MALFORMED)
        self.assertEqual(result.calls, [])

    def test_classifies_noncanonical_wrapper_as_malformed(self):
        text = """I will tap the target.
<function_calls >
<invoke name="click"><parameter name="index">12</parameter></invoke>
</function_calls>"""

        result = parse_tool_calls_detailed(text, {"index": "number"})

        self.assertEqual(result.status, ToolCallParseStatus.MALFORMED)
        self.assertEqual(result.calls, [])

    def test_classifies_missing_close_tag_as_malformed(self):
        text = """I will tap the target.
<function_calls>
<invoke name="click"><parameter name="index">12</parameter></invoke>"""

        result = parse_tool_calls_detailed(text, {"index": "number"})

        self.assertEqual(result.status, ToolCallParseStatus.MALFORMED)
        self.assertEqual(result.calls, [])

    def test_classifies_empty_wrapper_as_malformed(self):
        result = parse_tool_calls_detailed("<function_calls>\n</function_calls>")

        self.assertEqual(result.status, ToolCallParseStatus.MALFORMED)
        self.assertEqual(result.calls, [])

    def test_classifies_wrapper_without_named_invoke_as_malformed(self):
        text = """<function_calls>
<invoke><parameter name="index">12</parameter></invoke>
</function_calls>"""

        result = parse_tool_calls_detailed(text, {"index": "number"})

        self.assertEqual(result.status, ToolCallParseStatus.MALFORMED)
        self.assertEqual(result.calls, [])

    def test_classifies_valid_xml_as_valid(self):
        text = """I will tap the target.
<function_calls>
<invoke name="click"><parameter name="index">12</parameter></invoke>
</function_calls>"""

        result = parse_tool_calls_detailed(text, {"index": "number"})

        self.assertEqual(result.status, ToolCallParseStatus.VALID)
        self.assertEqual(len(result.calls), 1)
        self.assertEqual(result.calls[0].parameters, {"index": 12})

    def test_valid_sibling_block_wins_over_malformed_block(self):
        text = """I will retry and then complete.
<function_calls>
<｜｜DSML｜｜ name="click">
<parameter name="index">12</parameter>
</｜｜DSML｜｜>
</function_calls>
<function_calls>
<invoke name="complete">
<parameter name="success">true</parameter>
<parameter name="message">Done</parameter>
</invoke>
</function_calls>"""

        result = parse_tool_calls_detailed(text, {"success": "boolean"})

        self.assertEqual(result.status, ToolCallParseStatus.VALID)
        self.assertEqual([call.name for call in result.calls], ["complete"])
        self.assertEqual(
            result.calls[0].parameters,
            {"success": True, "message": "Done"},
        )

    def test_argument_error_is_valid_markup(self):
        text = """<function_calls>
<invoke name="click"><parameter name="index">not-a-number</parameter></invoke>
</function_calls>"""

        result = parse_tool_calls_detailed(text, {"index": "number"})

        self.assertEqual(result.status, ToolCallParseStatus.VALID)
        self.assertEqual(len(result.calls), 1)
        self.assertIsNotNone(result.calls[0].error)

    def test_public_tuple_parser_remains_compatible(self):
        text = """I will tap the target.
<function_calls>
<invoke name="click"><parameter name="index">12</parameter></invoke>
</function_calls>"""

        thought, calls = parse_tool_calls(text, {"index": "number"})
        detailed = parse_tool_calls_detailed(text, {"index": "number"})

        self.assertEqual((thought, calls), (detailed.thought, detailed.calls))

    def test_public_tuple_parser_preserves_marker_only_text(self):
        cases = [
            "I will tap.\n<｜DSML｜tool_calls></｜DSML｜tool_calls>",
            'I will tap.\n<invoke name="click"></invoke>',
            "I will tap.\n<function_calls ></function_calls>",
        ]

        for text in cases:
            with self.subTest(text=text):
                thought, calls = parse_tool_calls(text)
                self.assertEqual(thought, text.strip())
                self.assertEqual(calls, [])

    def test_drops_adjacent_exact_duplicate_tool_calls(self):
        text = """
I will tap the target.
<function_calls>
<invoke name="click_at">
<parameter name="x">128</parameter>
<parameter name="y">1560</parameter>
</invoke>
</function_calls>
<function_calls>
<invoke name="click_at">
<parameter name="x">128</parameter>
<parameter name="y">1560</parameter>
</invoke>
</function_calls>
"""

        thought, calls = parse_tool_calls(text, {"x": "number", "y": "number"})

        self.assertIn("I will tap", thought)
        self.assertEqual(len(calls), 1)
        self.assertEqual(calls[0].name, "click_at")
        self.assertEqual(calls[0].parameters, {"x": 128, "y": 1560})

    def test_keeps_non_identical_sequential_calls(self):
        text = """
I will tap two different targets.
<function_calls>
<invoke name="click_at">
<parameter name="x">128</parameter>
<parameter name="y">1560</parameter>
</invoke>
<invoke name="click_at">
<parameter name="x">200</parameter>
<parameter name="y">1560</parameter>
</invoke>
</function_calls>
"""

        _, calls = parse_tool_calls(text, {"x": "number", "y": "number"})

        self.assertEqual(len(calls), 2)
        self.assertEqual(calls[0].parameters, {"x": 128, "y": 1560})
        self.assertEqual(calls[1].parameters, {"x": 200, "y": 1560})

    def test_keeps_identical_invokes_inside_one_block(self):
        text = """
I will press back twice.
<function_calls>
<invoke name="system_button">
<parameter name="button">back</parameter>
</invoke>
<invoke name="system_button">
<parameter name="button">back</parameter>
</invoke>
</function_calls>
"""

        _, calls = parse_tool_calls(text)

        self.assertEqual(len(calls), 2)
        self.assertEqual(
            [call.name for call in calls], ["system_button", "system_button"]
        )
        self.assertEqual(calls[0].parameters, {"button": "back"})
        self.assertEqual(calls[1].parameters, {"button": "back"})

    def test_keeps_intentional_mixed_batch(self):
        text = """
I will focus the field and type.
<function_calls>
<invoke name="click_at">
<parameter name="x">261</parameter>
<parameter name="y">1888</parameter>
</invoke>
<invoke name="type_text">
<parameter name="text">Android version</parameter>
<parameter name="clear">true</parameter>
</invoke>
</function_calls>
"""

        _, calls = parse_tool_calls(
            text,
            {"x": "number", "y": "number", "clear": "boolean"},
        )

        self.assertEqual([call.name for call in calls], ["click_at", "type_text"])
        self.assertEqual(calls[0].parameters, {"x": 261, "y": 1888})
        self.assertEqual(
            calls[1].parameters,
            {"text": "Android version", "clear": True},
        )

    def test_duplicate_complete_blocks_execute_once(self):
        text = """
The task is done.
<function_calls>
<invoke name="complete">
<parameter name="success">true</parameter>
<parameter name="message">Done</parameter>
</invoke>
</function_calls>
<function_calls>
<invoke name="complete">
<parameter name="success">true</parameter>
<parameter name="message">Done</parameter>
</invoke>
</function_calls>
"""

        _, calls = parse_tool_calls(text, {"success": "boolean"})

        self.assertEqual(len(calls), 1)
        self.assertEqual(calls[0].name, "complete")
        self.assertEqual(calls[0].parameters, {"success": True, "message": "Done"})

    def test_formatted_tool_calls_use_deduped_calls(self):
        text = """
Tap once.
<function_calls>
<invoke name="click_at">
<parameter name="x">128</parameter>
<parameter name="y">1560</parameter>
</invoke>
</function_calls>
<function_calls>
<invoke name="click_at">
<parameter name="x">128</parameter>
<parameter name="y">1560</parameter>
</invoke>
</function_calls>
"""

        _, calls = parse_tool_calls(text, {"x": "number", "y": "number"})
        formatted = format_tool_calls(calls)

        self.assertEqual(formatted.count('<invoke name="click_at">'), 1)
        self.assertIn('<parameter name="x">128</parameter>', formatted)
        self.assertIn('<parameter name="y">1560</parameter>', formatted)

    def test_extract_add_memory_basic(self):
        text = "I see the email.\n<add_memory>Meeting at 3pm Thursday Room 204</add_memory>\nNow I'll click reply."
        result = extract_add_memory(text)
        self.assertEqual(result, "Meeting at 3pm Thursday Room 204")

    def test_extract_add_memory_empty(self):
        text = "Just a thought with no memory tag."
        result = extract_add_memory(text)
        self.assertEqual(result, "")

    def test_extract_add_memory_whitespace(self):
        text = "<add_memory>  spaced content  </add_memory>"
        result = extract_add_memory(text)
        self.assertEqual(result, "spaced content")

    def test_extract_add_memory_multiline(self):
        text = """Some thought here.
<add_memory>
Line 1: Meeting at 3pm
Line 2: Room 204
</add_memory>
Tool calls follow."""
        result = extract_add_memory(text)
        self.assertIn("Meeting at 3pm", result)
        self.assertIn("Room 204", result)

    def test_extract_add_memory_with_tool_calls(self):
        text = """I see the password field.
<add_memory>Username is admin@test.com</add_memory>
<function_calls>
<invoke name="click"><parameter name="index">5</parameter></invoke>
</function_calls>"""
        thought, calls = parse_tool_calls(text, {"index": "number"})
        memory = extract_add_memory(thought)
        self.assertEqual(memory, "Username is admin@test.com")
        self.assertEqual(len(calls), 1)
        self.assertEqual(calls[0].name, "click")

    def test_extract_add_memory_multiple_blocks(self):
        text = """I found two important things on this screen.
<add_memory>User email is a@example.com</add_memory>
<add_memory>Verification code is 123456</add_memory>"""
        result = extract_add_memory(text)
        self.assertIn("User email is a@example.com", result)
        self.assertIn("Verification code is 123456", result)


if __name__ == "__main__":
    unittest.main()
