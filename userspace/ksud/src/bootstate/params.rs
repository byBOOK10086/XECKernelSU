//! Replace only existing boot-state parameters; never invent device parameters.

const OVERRIDES: &[(&str, &str)] = &[
    ("androidboot.verifiedbootstate", "green"),
    ("androidboot.vbmeta.device_state", "locked"),
    ("androidboot.flash.locked", "1"),
    ("androidboot.veritymode", "enforcing"),
    ("androidboot.secureboot", "1"),
    ("androidboot.vbmeta.invalidate_on_error", "yes"),
];

fn override_value(key: &str) -> Option<&'static str> {
    OVERRIDES
        .iter()
        .find(|(name, _)| *name == key)
        .map(|(_, value)| *value)
}

pub(super) fn rewrite_cmdline(original: &str) -> String {
    // Preserve whitespace and unrelated tokens exactly.
    original
        .split_inclusive(char::is_whitespace)
        .map(|part| {
            let token = part.trim_end_matches(char::is_whitespace);
            let whitespace = &part[token.len()..];
            token
                .split_once('=')
                .and_then(|(key, _)| {
                    override_value(key).map(|value| format!("{key}={value}{whitespace}"))
                })
                .unwrap_or_else(|| part.to_owned())
        })
        .collect()
}

pub(super) fn rewrite_bootconfig(original: &str) -> String {
    original
        .split_inclusive('\n')
        .map(|line| {
            let Some((key, _)) = line.split_once('=') else {
                return line.to_owned();
            };
            let Some(value) = override_value(key.trim()) else {
                return line.to_owned();
            };
            let newline = if line.ends_with('\n') { "\n" } else { "" };
            format!("{key}= \"{value}\"{newline}")
        })
        .collect::<Vec<_>>()
        .concat()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn preserves_absent_and_unrelated_parameters() {
        for source in ["", "console=ttyS0  androidboot.hardware=test\n"] {
            assert_eq!(rewrite_cmdline(source), source);
            assert_eq!(rewrite_bootconfig(source), source);
        }
    }

    #[test]
    fn cmdline_replaces_existing_tokens_only_and_is_idempotent() {
        let source = "console=ttyS0  androidboot.verifiedbootstate=orange\n";
        let expected = "console=ttyS0  androidboot.verifiedbootstate=green\n";
        assert_eq!(rewrite_cmdline(source), expected);
        assert_eq!(rewrite_cmdline(expected), expected);
    }

    #[test]
    fn bootconfig_values_are_quoted_and_idempotent() {
        let source = "androidboot.verifiedbootstate = \"orange\"\nother = \"value\"\nandroidboot.flash.locked = 0";
        let expected = "androidboot.verifiedbootstate = \"green\"\nother = \"value\"\nandroidboot.flash.locked = \"1\"";
        assert_eq!(rewrite_bootconfig(source), expected);
        assert_eq!(rewrite_bootconfig(expected), expected);
    }
}
