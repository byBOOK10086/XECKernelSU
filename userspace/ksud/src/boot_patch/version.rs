//! Pure kernel release parsing and GKI version policy.

pub(super) fn parse_kernel_version(release: &str) -> Option<(u32, u32, u32)> {
    let mut parts = release.split('.');
    let major = parts.next()?.parse().ok()?;
    let minor = parts.next()?.parse().ok()?;
    let patch = parts.next()?.split(|c: char| !c.is_ascii_digit()).next()?;
    Some((major, minor, patch.parse().ok()?))
}

pub(super) const fn is_gki_version((major, minor, _): (u32, u32, u32)) -> bool {
    major > 5 || (major == 5 && minor >= 10)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn gki_uses_major_and_minor_not_patch() {
        for (release, expected) in [
            ("4.19.157", false),
            ("4.14.3", false),
            ("5.4.250", false),
            ("5.9.999", false),
            ("5.10.0", true),
            ("5.15.2-android13-8", true),
            ("6.1.0", true),
            ("6.1.3", true),
            ("6.6.5", true),
        ] {
            assert_eq!(
                is_gki_version(parse_kernel_version(release).unwrap()),
                expected,
                "{release}"
            );
        }
    }

    #[test]
    fn rejects_invalid_releases() {
        for release in ["", "Linux", "5.10", "5.x.1", "6.1.-1", "-6.1.0"] {
            assert!(parse_kernel_version(release).is_none(), "{release}");
        }
    }
}
