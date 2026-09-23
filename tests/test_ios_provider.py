import asyncio

from mobilerun.tools.ui.ios_provider import IOSStateProvider

RAW_SETTINGS_A11Y = """Attributes: Application, pid: 123, label: 'Settings'
Element subtree:
 ->Application, pid: 123, label: 'Settings'
    Window (Main), {{0.0, 0.0}, {440.0, 956.0}}
      NavigationBar, {{0.0, 62.0}, {440.0, 106.0}}, identifier: 'Settings'
        StaticText, {{20.0, 119.7}, {133.0, 40.7}}, label: 'Settings'
      SearchField, {{17.0, 583.0}, {346.0, 38.0}}, placeholderValue: 'Search'
"""


class FakeIOSDriver:
    async def get_ui_tree(self):
        return {
            "phone_state": {
                "currentApp": "Settings",
                "packageName": "com.apple.Preferences",
                "keyboardVisible": False,
            },
            "device_context": {
                "screen_bounds": {
                    "width": 440,
                    "height": 956,
                },
            },
            "a11y_tree": [
                {
                    "type": "Application",
                    "text": "Settings",
                    "children": [],
                }
            ],
            "raw_a11y_tree": RAW_SETTINGS_A11Y,
        }


def test_ios_state_provider_reads_raw_a11y_tree_from_normalized_driver_state():
    state = asyncio.run(IOSStateProvider(FakeIOSDriver()).get_state())

    assert state.screen_width == 440
    assert state.screen_height == 956
    assert state.phone_state["currentApp"] == "Settings"
    assert state.phone_state["packageName"] == "com.apple.Preferences"
    assert any(element["className"] == "SearchField" for element in state.elements)
    assert "SearchField" in state.formatted_text
