import os
import stat

import pytest
import yaml

from mobilerun.config_manager.loader import ConfigLoader


@pytest.mark.skipif(os.name == "nt", reason="POSIX file modes are not available")
def test_save_dict_restricts_existing_config_to_owner_only(tmp_path):
    config_path = tmp_path / "config.yaml"
    config_path.write_text(f"old: {'value' * 20}\n")
    os.chmod(config_path, 0o644)
    config = {"_version": 7, "device": {"auth_token": "secret"}}

    ConfigLoader._save_dict(config, config_path)

    assert yaml.safe_load(config_path.read_text()) == config
    assert stat.S_IMODE(config_path.stat().st_mode) == 0o600
