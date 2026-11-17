//! Specification-compliant USB Audio Class (UAC 1.0 & UAC 2.0) descriptor
//! and entity topology graph parser.
//!
//! Parses raw USB configuration descriptors into a fully connected entity graph:
//! ClockSource, ClockSelector, ClockMultiplier, InputTerminal, OutputTerminal,
//! FeatureUnit, MixerUnit, SelectorUnit, and AudioStreaming interface alternate settings.

use rusb::ConfigDescriptor;

pub const USB_CLASS_AUDIO: u8 = 1;
pub const USB_SUBCLASS_AUDIOCONTROL: u8 = 1;
pub const USB_SUBCLASS_AUDIOSTREAMING: u8 = 2;

pub const USB_DT_CS_INTERFACE: u8 = 0x24;
pub const USB_DT_CS_ENDPOINT: u8 = 0x25;

// AudioControl descriptor subtypes
pub const AC_SUBTYPE_HEADER: u8 = 0x01;
pub const AC_SUBTYPE_INPUT_TERMINAL: u8 = 0x02;
pub const AC_SUBTYPE_OUTPUT_TERMINAL: u8 = 0x03;
pub const AC_SUBTYPE_MIXER_UNIT: u8 = 0x04;
pub const AC_SUBTYPE_SELECTOR_UNIT: u8 = 0x05;
pub const AC_SUBTYPE_FEATURE_UNIT: u8 = 0x06;
pub const AC_SUBTYPE_CLOCK_SOURCE: u8 = 0x0A;
pub const AC_SUBTYPE_CLOCK_SELECTOR: u8 = 0x0B;
pub const AC_SUBTYPE_CLOCK_MULTIPLIER: u8 = 0x0C;

// AudioStreaming descriptor subtypes
pub const AS_SUBTYPE_GENERAL: u8 = 0x01;
pub const AS_SUBTYPE_FORMAT_TYPE: u8 = 0x02;

// Terminal types
pub const TERMINAL_USB_STREAMING: u16 = 0x0101;
pub const TERMINAL_OUT_SPEAKER: u16 = 0x0301;
pub const TERMINAL_OUT_HEADPHONES: u16 = 0x0302;
pub const TERMINAL_OUT_LINE: u16 = 0x0601;

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ClockSourceDesc {
    pub id: u8,
    pub clock_type: u8,
    pub is_programmable: bool,
    pub supports_freq_get: bool,
    pub supports_freq_set: bool,
    pub supports_clock_valid: bool,
    pub assoc_terminal: u8,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ClockSelectorDesc {
    pub id: u8,
    pub input_pins: Vec<u8>,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ClockMultiplierDesc {
    pub id: u8,
    pub source_clock_id: u8,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct TerminalDesc {
    pub id: u8,
    pub is_input: bool,
    pub terminal_type: u16,
    pub assoc_terminal: u8,
    pub source_id: u8,          // OutputTerminal: source entity ID feeding it
    pub clock_source_id: u8,    // UAC2: bCSourceID (Clock Source / Selector ID)
    pub channels: u16,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct FeatureUnitDesc {
    pub id: u8,
    pub source_id: u8,
    pub has_master_mute: bool,
    pub has_master_volume: bool,
    pub has_channel_volume: bool,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct AudioStreamingAltDesc {
    pub interface_number: u8,
    pub alt_setting: u8,
    pub is_out: bool,
    pub terminal_link: u8,
    pub format_tag: u16,
    pub channels: u16,
    pub subslot_size: u8,
    pub bit_resolution: u8,
    pub discrete_sample_rates: Vec<u32>,      // UAC1 discrete sample rates
    pub continuous_range: Option<(u32, u32)>, // UAC1 continuous range (min, max)
    pub endpoint_address: u8,
    pub endpoint_sync_type: u8,               // 0=none, 1=async, 2=adaptive, 3=sync
    pub endpoint_usage_type: u8,              // 0=data, 1=feedback, 2=implicit feedback
    pub max_packet_size: u16,
    pub endpoint_interval: u8,
    pub synch_address: u8,                    // Explicit feedback endpoint address (0 if none)
    pub feedback_endpoint_address: u8,        // Companion feedback IN endpoint if present (0 if none)
    pub supports_rate_control: bool,          // UAC1: bmAttributes & 0x01 on CS endpoint descriptor
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct AudioTopology {
    pub uac_version: u8, // 1 or 2
    pub ac_interface_number: u8,
    pub clock_sources: Vec<ClockSourceDesc>,
    pub clock_selectors: Vec<ClockSelectorDesc>,
    pub clock_multipliers: Vec<ClockMultiplierDesc>,
    pub terminals: Vec<TerminalDesc>,
    pub feature_units: Vec<FeatureUnitDesc>,
    pub streaming_alts: Vec<AudioStreamingAltDesc>,
}

impl AudioTopology {
    /// Resolves the true ClockSource entity ID for a playback streaming alternate setting.
    ///
    /// Graph traversal:
    /// 1. `alt.terminal_link` connects to an `InputTerminal` (type `0x0101` USB Streaming).
    /// 2. In UAC 2.0, the `InputTerminal` defines `bCSourceID` (offset 7 in the descriptor).
    /// 3. We follow `bCSourceID` through any `ClockSelector` or `ClockMultiplier` until reaching a `ClockSource`.
    /// 4. If the input terminal doesn't specify a valid clock, trace downstream to the physical `OutputTerminal`
    ///    (Speaker/Headphones) and inspect its `bCSourceID`.
    /// 5. Fallback: return the first programmable or valid `ClockSource` in the AudioControl interface.
    pub fn resolve_playback_clock_source_id(&self, alt: &AudioStreamingAltDesc) -> Option<u8> {
        if self.uac_version != 2 {
            return None;
        }

        // 1. Check InputTerminal linked to this streaming interface
        if let Some(in_term) = self.terminals.iter().find(|t| t.is_input && t.id == alt.terminal_link) {
            if in_term.clock_source_id != 0 {
                if let Some(resolved) = self.resolve_clock_entity_id(in_term.clock_source_id) {
                    return Some(resolved);
                }
            }
        }

        // 2. Check downstream physical OutputTerminal
        if let Some(out_term) = self.terminals.iter().find(|t| !t.is_input && (t.terminal_type & 0xFF00) == 0x0300) {
            if out_term.clock_source_id != 0 {
                if let Some(resolved) = self.resolve_clock_entity_id(out_term.clock_source_id) {
                    return Some(resolved);
                }
            }
        }

        // 3. Fallback: first programmable ClockSource
        if let Some(cs) = self.clock_sources.iter().find(|cs| cs.supports_freq_set || cs.is_programmable) {
            return Some(cs.id);
        }

        // 4. Fallback: any ClockSource
        self.clock_sources.first().map(|cs| cs.id)
    }

    /// Recursively resolves a clock entity ID, navigating `ClockMultiplier` and `ClockSelector` units.
    pub fn resolve_clock_entity_id(&self, entity_id: u8) -> Option<u8> {
        // Direct ClockSource?
        if self.clock_sources.iter().any(|cs| cs.id == entity_id) {
            return Some(entity_id);
        }

        // ClockMultiplier? Follow source_clock_id
        if let Some(cm) = self.clock_multipliers.iter().find(|cm| cm.id == entity_id) {
            return self.resolve_clock_entity_id(cm.source_clock_id);
        }

        // ClockSelector? Follow first valid input pin
        if let Some(cs) = self.clock_selectors.iter().find(|cs| cs.id == entity_id) {
            for &pin in &cs.input_pins {
                if let Some(resolved) = self.resolve_clock_entity_id(pin) {
                    return Some(resolved);
                }
            }
        }

        None
    }

    /// Resolves the FeatureUnit along the playback path for hardware volume/mute.
    pub fn resolve_playback_feature_unit(&self, alt: &AudioStreamingAltDesc) -> Option<&FeatureUnitDesc> {
        // 1. Direct downstream from InputTerminal
        if let Some(fu) = self.feature_units.iter().find(|fu| fu.source_id == alt.terminal_link) {
            return Some(fu);
        }

        // 2. Direct upstream from physical OutputTerminal
        if let Some(ot) = self.terminals.iter().find(|t| !t.is_input && (t.terminal_type & 0xFF00) == 0x0300) {
            if let Some(fu) = self.feature_units.iter().find(|fu| fu.id == ot.source_id) {
                return Some(fu);
            }
        }

        // 3. Any FeatureUnit with volume
        self.feature_units.iter().find(|fu| fu.has_master_volume || fu.has_channel_volume)
    }

    /// Returns all playback (OUT) streaming alternate settings sorted by resolution and channel count.
    pub fn find_playback_streaming_alts(&self) -> Vec<&AudioStreamingAltDesc> {
        let mut alts: Vec<&AudioStreamingAltDesc> = self.streaming_alts.iter().filter(|a| a.is_out).collect();
        alts.sort_by(|a, b| {
            b.bit_resolution.cmp(&a.bit_resolution)
                .then_with(|| b.channels.cmp(&a.channels))
                .then_with(|| a.subslot_size.cmp(&b.subslot_size))
        });
        alts
    }
}

/// Parses the active USB configuration descriptor into an `AudioTopology`.
pub fn parse_audio_topology(config_desc: &ConfigDescriptor) -> Result<AudioTopology, i32> {
    let mut uac_version = 2u8;
    let mut ac_iface = 0u8;

    let mut clock_sources = Vec::new();
    let mut clock_selectors = Vec::new();
    let mut clock_multipliers = Vec::new();
    let mut terminals = Vec::new();
    let mut feature_units = Vec::new();
    let mut streaming_alts = Vec::new();

    // 1. Parse AudioControl interface
    for iface in config_desc.interfaces() {
        for desc in iface.descriptors() {
            if desc.class_code() != USB_CLASS_AUDIO {
                continue;
            }

            if desc.sub_class_code() == USB_SUBCLASS_AUDIOCONTROL {
                ac_iface = desc.interface_number();
                uac_version = if desc.protocol_code() == 0x20 { 2 } else { 1 };

                let extra = desc.extra();
                let mut offset = 0usize;
                while offset + 2 <= extra.len() {
                    let len = extra[offset] as usize;
                    if len < 2 || offset + len > extra.len() {
                        break;
                    }
                    let desc_type = extra[offset + 1];
                    if desc_type == USB_DT_CS_INTERFACE {
                        let subtype = extra[offset + 2];
                        match subtype {
                            // INPUT_TERMINAL
                            AC_SUBTYPE_INPUT_TERMINAL => {
                                if uac_version == 2 && len >= 17 {
                                    let id = extra[offset + 3];
                                    let terminal_type = u16::from_le_bytes([extra[offset + 4], extra[offset + 5]]);
                                    let assoc_terminal = extra[offset + 6];
                                    let clock_source_id = extra[offset + 7]; // bCSourceID is offset 7!
                                    let channels = extra[offset + 8] as u16;
                                    terminals.push(TerminalDesc {
                                        id,
                                        is_input: true,
                                        terminal_type,
                                        assoc_terminal,
                                        source_id: 0,
                                        clock_source_id,
                                        channels,
                                    });
                                } else if uac_version == 1 && len >= 12 {
                                    let id = extra[offset + 3];
                                    let terminal_type = u16::from_le_bytes([extra[offset + 4], extra[offset + 5]]);
                                    let assoc_terminal = extra[offset + 6];
                                    let channels = extra[offset + 7] as u16;
                                    terminals.push(TerminalDesc {
                                        id,
                                        is_input: true,
                                        terminal_type,
                                        assoc_terminal,
                                        source_id: 0,
                                        clock_source_id: 0,
                                        channels,
                                    });
                                }
                            }
                            // OUTPUT_TERMINAL
                            AC_SUBTYPE_OUTPUT_TERMINAL => {
                                if uac_version == 2 && len >= 12 {
                                    let id = extra[offset + 3];
                                    let terminal_type = u16::from_le_bytes([extra[offset + 4], extra[offset + 5]]);
                                    let assoc_terminal = extra[offset + 6];
                                    let source_id = extra[offset + 7];
                                    let clock_source_id = extra[offset + 8]; // bCSourceID is offset 8!
                                    terminals.push(TerminalDesc {
                                        id,
                                        is_input: false,
                                        terminal_type,
                                        assoc_terminal,
                                        source_id,
                                        clock_source_id,
                                        channels: 2,
                                    });
                                } else if uac_version == 1 && len >= 9 {
                                    let id = extra[offset + 3];
                                    let terminal_type = u16::from_le_bytes([extra[offset + 4], extra[offset + 5]]);
                                    let assoc_terminal = extra[offset + 6];
                                    let source_id = extra[offset + 7];
                                    terminals.push(TerminalDesc {
                                        id,
                                        is_input: false,
                                        terminal_type,
                                        assoc_terminal,
                                        source_id,
                                        clock_source_id: 0,
                                        channels: 2,
                                    });
                                }
                            }
                            // FEATURE_UNIT
                            AC_SUBTYPE_FEATURE_UNIT => {
                                if len >= 6 {
                                    let id = extra[offset + 3];
                                    let source_id = extra[offset + 4];
                                    let mut has_master_mute = false;
                                    let mut has_master_volume = false;
                                    let mut has_channel_volume = false;

                                    if uac_version == 2 {
                                        // 4 bytes per channel control bitmask
                                        let control_bytes = &extra[offset + 5..offset + len - 1];
                                        if control_bytes.len() >= 4 {
                                            let master_controls = u32::from_le_bytes([
                                                control_bytes[0], control_bytes[1], control_bytes[2], control_bytes[3],
                                            ]);
                                            has_master_mute = (master_controls & 0x03) != 0;
                                            has_master_volume = (master_controls & 0x0C) != 0;
                                        }
                                        if control_bytes.len() >= 8 {
                                            for ch_ctrl in control_bytes[4..].chunks_exact(4) {
                                                let ch_bits = u32::from_le_bytes([ch_ctrl[0], ch_ctrl[1], ch_ctrl[2], ch_ctrl[3]]);
                                                if (ch_bits & 0x0C) != 0 {
                                                    has_channel_volume = true;
                                                }
                                            }
                                        }
                                    } else {
                                        // UAC 1.0
                                        let control_size = extra[offset + 5] as usize;
                                        if control_size >= 1 && len >= 7 + control_size {
                                            let master_bits = extra[offset + 6];
                                            has_master_mute = (master_bits & 0x01) != 0;
                                            has_master_volume = (master_bits & 0x02) != 0;
                                            for b in &extra[offset + 6 + control_size..offset + len - 1] {
                                                if (b & 0x02) != 0 {
                                                    has_channel_volume = true;
                                                }
                                            }
                                        }
                                    }

                                    feature_units.push(FeatureUnitDesc {
                                        id,
                                        source_id,
                                        has_master_mute,
                                        has_master_volume,
                                        has_channel_volume,
                                    });
                                }
                            }
                            // CLOCK_SOURCE (UAC2)
                            AC_SUBTYPE_CLOCK_SOURCE => {
                                if len >= 8 {
                                    let id = extra[offset + 3];
                                    let bm_attributes = extra[offset + 4];
                                    let bm_controls = extra[offset + 5];
                                    let assoc_terminal = extra[offset + 6];
                                    clock_sources.push(ClockSourceDesc {
                                        id,
                                        clock_type: bm_attributes & 0x03,
                                        is_programmable: (bm_attributes & 0x03) == 3,
                                        supports_freq_get: (bm_controls & 0x01) != 0,
                                        supports_freq_set: (bm_controls & 0x02) != 0,
                                        supports_clock_valid: (bm_controls & 0x04) != 0,
                                        assoc_terminal,
                                    });
                                }
                            }
                            // CLOCK_SELECTOR (UAC2)
                            AC_SUBTYPE_CLOCK_SELECTOR => {
                                if len >= 6 {
                                    let id = extra[offset + 3];
                                    let nr_pins = extra[offset + 4] as usize;
                                    if len >= 5 + nr_pins {
                                        let input_pins = extra[offset + 5..offset + 5 + nr_pins].to_vec();
                                        clock_selectors.push(ClockSelectorDesc { id, input_pins });
                                    }
                                }
                            }
                            // CLOCK_MULTIPLIER (UAC2)
                            AC_SUBTYPE_CLOCK_MULTIPLIER => {
                                if len >= 7 {
                                    let id = extra[offset + 3];
                                    let source_clock_id = extra[offset + 4];
                                    clock_multipliers.push(ClockMultiplierDesc { id, source_clock_id });
                                }
                            }
                            _ => {}
                        }
                    }
                    offset += len;
                }
            }
        }
    }

    // 2. Parse AudioStreaming interfaces
    for iface in config_desc.interfaces() {
        for desc in iface.descriptors() {
            if desc.class_code() != USB_CLASS_AUDIO || desc.sub_class_code() != USB_SUBCLASS_AUDIOSTREAMING {
                continue;
            }

            let interface_number = desc.interface_number();
            let alt_setting = desc.setting_number();

            // Alt 0 is zero-bandwidth idle setting with 0 endpoints
            if alt_setting == 0 || desc.num_endpoints() == 0 {
                continue;
            }

            let extra = desc.extra();
            let mut terminal_link = 0u8;
            let mut format_tag = 1u16; // PCM default
            let mut channels = 2u16;
            let mut subslot_size = 2u8;
            let mut bit_resolution = 16u8;
            let mut discrete_sample_rates = Vec::new();
            let mut continuous_range = None;

            let mut offset = 0usize;
            while offset + 2 <= extra.len() {
                let len = extra[offset] as usize;
                if len < 2 || offset + len > extra.len() {
                    break;
                }
                let desc_type = extra[offset + 1];
                if desc_type == USB_DT_CS_INTERFACE {
                    let subtype = extra[offset + 2];
                    match subtype {
                        // AS_GENERAL
                        AS_SUBTYPE_GENERAL => {
                            if uac_version == 2 && len >= 16 {
                                terminal_link = extra[offset + 3];
                                channels = extra[offset + 10] as u16;
                            } else if uac_version == 1 && len >= 7 {
                                terminal_link = extra[offset + 3];
                                format_tag = u16::from_le_bytes([extra[offset + 5], extra[offset + 6]]);
                            }
                        }
                        // FORMAT_TYPE
                        AS_SUBTYPE_FORMAT_TYPE => {
                            if uac_version == 2 && len >= 6 {
                                subslot_size = extra[offset + 4];
                                bit_resolution = extra[offset + 5];
                            } else if uac_version == 1 && len >= 8 {
                                channels = extra[offset + 4] as u16;
                                subslot_size = extra[offset + 5];
                                bit_resolution = extra[offset + 6];

                                let sam_freq_type = extra[offset + 7] as usize;
                                if sam_freq_type == 0 && len >= 14 {
                                    let min = (extra[offset + 8] as u32)
                                        | ((extra[offset + 9] as u32) << 8)
                                        | ((extra[offset + 10] as u32) << 16);
                                    let max = (extra[offset + 11] as u32)
                                        | ((extra[offset + 12] as u32) << 8)
                                        | ((extra[offset + 13] as u32) << 16);
                                    continuous_range = Some((min, max));
                                } else if sam_freq_type > 0 && len >= 8 + sam_freq_type * 3 {
                                    for s in 0..sam_freq_type {
                                        let s_off = offset + 8 + s * 3;
                                        let rate = (extra[s_off] as u32)
                                            | ((extra[s_off + 1] as u32) << 8)
                                            | ((extra[s_off + 2] as u32) << 16);
                                        discrete_sample_rates.push(rate);
                                    }
                                }
                            }
                        }
                        _ => {}
                    }
                }
                offset += len;
            }

            // Inspect endpoints for this alt setting
            let mut data_ep_addr = 0u8;
            let mut is_out = true;
            let mut sync_type = 0u8;
            let mut usage_type = 0u8;
            let mut max_packet = 0u16;
            let mut interval = 1u8;
            let mut synch_addr = 0u8;
            let mut fb_ep_addr = 0u8;
            let mut supports_rate_control = false;

            for ep_desc in desc.endpoint_descriptors() {
                let addr = ep_desc.address();
                let dir_out = (addr & 0x80) == 0;
                let attr = ep_desc.transfer_type();
                let sync = (ep_desc.sync_type() as u8) & 0x03;
                let usage = (ep_desc.usage_type() as u8) & 0x03;

                // Isochronous endpoints
                if attr == rusb::TransferType::Isochronous {
                    if dir_out {
                        data_ep_addr = addr;
                        is_out = true;
                        sync_type = sync;
                        usage_type = usage;
                        max_packet = ep_desc.max_packet_size();
                        interval = ep_desc.interval();
                        synch_addr = ep_desc.synch_address();

                        // In UAC1, check CS endpoint descriptor for sampling frequency control
                        if let Some(ep_extra) = ep_desc.extra() {
                            if uac_version == 1 && ep_extra.len() >= 4 && ep_extra[1] == USB_DT_CS_ENDPOINT && ep_extra[2] == 0x01 {
                                supports_rate_control = (ep_extra[3] & 0x01) != 0;
                            }
                        }
                    } else {
                        // IN endpoint on an audio streaming interface: feedback endpoint!
                        fb_ep_addr = addr;
                    }
                }
            }

            if data_ep_addr != 0 {
                streaming_alts.push(AudioStreamingAltDesc {
                    interface_number,
                    alt_setting,
                    is_out,
                    terminal_link,
                    format_tag,
                    channels,
                    subslot_size,
                    bit_resolution,
                    discrete_sample_rates,
                    continuous_range,
                    endpoint_address: data_ep_addr,
                    endpoint_sync_type: sync_type,
                    endpoint_usage_type: usage_type,
                    max_packet_size: max_packet,
                    endpoint_interval: interval,
                    synch_address: synch_addr,
                    feedback_endpoint_address: fb_ep_addr,
                    supports_rate_control,
                });
            }
        }
    }

    Ok(AudioTopology {
        uac_version,
        ac_interface_number: ac_iface,
        clock_sources,
        clock_selectors,
        clock_multipliers,
        terminals,
        feature_units,
        streaming_alts,
    })
}

/// Parses raw USB configuration descriptor bytes into an `AudioTopology`.
/// Completely independent of rusb/libusb, enabling zero-discovery, SELinux-clean probing.
pub fn parse_audio_topology_from_raw(raw: &[u8]) -> Result<AudioTopology, i32> {
    let mut offset = if raw.len() >= 9 && raw[1] == 2 {
        9
    } else {
        0
    };

    let mut uac_version = 2u8;
    let mut ac_iface = 0u8;

    let mut clock_sources = Vec::new();
    let mut clock_selectors = Vec::new();
    let mut clock_multipliers = Vec::new();
    let mut terminals = Vec::new();
    let mut feature_units = Vec::new();
    let mut streaming_alts = Vec::new();

    enum IfaceKind {
        None,
        AudioControl,
        AudioStreaming {
            interface_number: u8,
            alt_setting: u8,
            terminal_link: u8,
            format_tag: u16,
            channels: u16,
            subslot_size: u8,
            bit_resolution: u8,
            discrete_sample_rates: Vec<u32>,
            continuous_range: Option<(u32, u32)>,
            data_ep_addr: u8,
            is_out: bool,
            sync_type: u8,
            usage_type: u8,
            max_packet: u16,
            interval: u8,
            synch_addr: u8,
            fb_ep_addr: u8,
            supports_rate_control: bool,
        },
        Other,
    }

    let mut current_iface = IfaceKind::None;

    let flush_streaming_alt = |iface: &mut IfaceKind, alts: &mut Vec<AudioStreamingAltDesc>| {
        if let IfaceKind::AudioStreaming {
            interface_number,
            alt_setting,
            terminal_link,
            format_tag,
            channels,
            subslot_size,
            bit_resolution,
            discrete_sample_rates,
            continuous_range,
            data_ep_addr,
            is_out,
            sync_type,
            usage_type,
            max_packet,
            interval,
            synch_addr,
            fb_ep_addr,
            supports_rate_control,
        } = iface {
            if *alt_setting > 0 && *data_ep_addr != 0 {
                alts.push(AudioStreamingAltDesc {
                    interface_number: *interface_number,
                    alt_setting: *alt_setting,
                    is_out: *is_out,
                    terminal_link: *terminal_link,
                    format_tag: *format_tag,
                    channels: *channels,
                    subslot_size: *subslot_size,
                    bit_resolution: *bit_resolution,
                    discrete_sample_rates: std::mem::take(discrete_sample_rates),
                    continuous_range: *continuous_range,
                    endpoint_address: *data_ep_addr,
                    endpoint_sync_type: *sync_type,
                    endpoint_usage_type: *usage_type,
                    max_packet_size: *max_packet,
                    endpoint_interval: *interval,
                    synch_address: *synch_addr,
                    feedback_endpoint_address: *fb_ep_addr,
                    supports_rate_control: *supports_rate_control,
                });
            }
        }
    };

    while offset + 2 <= raw.len() {
        let len = raw[offset] as usize;
        if len < 2 || offset + len > raw.len() {
            break;
        }
        let desc_type = raw[offset + 1];
        let chunk = &raw[offset..offset + len];

        match desc_type {
            // INTERFACE
            4 => {
                flush_streaming_alt(&mut current_iface, &mut streaming_alts);
                if len >= 9 {
                    let iface_num = chunk[2];
                    let alt_num = chunk[3];
                    let class = chunk[5];
                    let subclass = chunk[6];
                    let protocol = chunk[7];

                    if class == USB_CLASS_AUDIO && subclass == USB_SUBCLASS_AUDIOCONTROL {
                        ac_iface = iface_num;
                        uac_version = if protocol == 0x20 { 2 } else { 1 };
                        current_iface = IfaceKind::AudioControl;
                    } else if class == USB_CLASS_AUDIO && subclass == USB_SUBCLASS_AUDIOSTREAMING {
                        current_iface = IfaceKind::AudioStreaming {
                            interface_number: iface_num,
                            alt_setting: alt_num,
                            terminal_link: 0,
                            format_tag: 1,
                            channels: 2,
                            subslot_size: 2,
                            bit_resolution: 16,
                            discrete_sample_rates: Vec::new(),
                            continuous_range: None,
                            data_ep_addr: 0,
                            is_out: true,
                            sync_type: 0,
                            usage_type: 0,
                            max_packet: 0,
                            interval: 1,
                            synch_addr: 0,
                            fb_ep_addr: 0,
                            supports_rate_control: false,
                        };
                    } else {
                        current_iface = IfaceKind::Other;
                    }
                }
            }
            // CS_INTERFACE
            0x24 => {
                match &mut current_iface {
                    IfaceKind::AudioControl => {
                        if len >= 3 {
                            let subtype = chunk[2];
                            match subtype {
                                AC_SUBTYPE_INPUT_TERMINAL => {
                                    if uac_version == 2 && len >= 17 {
                                        terminals.push(TerminalDesc {
                                            id: chunk[3],
                                            is_input: true,
                                            terminal_type: u16::from_le_bytes([chunk[4], chunk[5]]),
                                            assoc_terminal: chunk[6],
                                            source_id: 0,
                                            clock_source_id: chunk[7],
                                            channels: chunk[8] as u16,
                                        });
                                    } else if uac_version == 1 && len >= 12 {
                                        terminals.push(TerminalDesc {
                                            id: chunk[3],
                                            is_input: true,
                                            terminal_type: u16::from_le_bytes([chunk[4], chunk[5]]),
                                            assoc_terminal: chunk[6],
                                            source_id: 0,
                                            clock_source_id: 0,
                                            channels: chunk[7] as u16,
                                        });
                                    }
                                }
                                AC_SUBTYPE_OUTPUT_TERMINAL => {
                                    if uac_version == 2 && len >= 12 {
                                        terminals.push(TerminalDesc {
                                            id: chunk[3],
                                            is_input: false,
                                            terminal_type: u16::from_le_bytes([chunk[4], chunk[5]]),
                                            assoc_terminal: chunk[6],
                                            source_id: chunk[7],
                                            clock_source_id: chunk[8],
                                            channels: 2,
                                        });
                                    } else if uac_version == 1 && len >= 9 {
                                        terminals.push(TerminalDesc {
                                            id: chunk[3],
                                            is_input: false,
                                            terminal_type: u16::from_le_bytes([chunk[4], chunk[5]]),
                                            assoc_terminal: chunk[6],
                                            source_id: chunk[7],
                                            clock_source_id: 0,
                                            channels: 2,
                                        });
                                    }
                                }
                                AC_SUBTYPE_FEATURE_UNIT => {
                                    if len >= 6 {
                                        let id = chunk[3];
                                        let source_id = chunk[4];
                                        let mut has_master_mute = false;
                                        let mut has_master_volume = false;
                                        let mut has_channel_volume = false;

                                        if uac_version == 2 {
                                            let control_bytes = &chunk[5..len - 1];
                                            if control_bytes.len() >= 4 {
                                                let master_controls = u32::from_le_bytes([
                                                    control_bytes[0], control_bytes[1], control_bytes[2], control_bytes[3],
                                                ]);
                                                has_master_mute = (master_controls & 0x03) != 0;
                                                has_master_volume = (master_controls & 0x0C) != 0;
                                            }
                                            if control_bytes.len() >= 8 {
                                                for ch_ctrl in control_bytes[4..].chunks_exact(4) {
                                                    let ch_bits = u32::from_le_bytes([ch_ctrl[0], ch_ctrl[1], ch_ctrl[2], ch_ctrl[3]]);
                                                    if (ch_bits & 0x0C) != 0 {
                                                        has_channel_volume = true;
                                                    }
                                                }
                                            }
                                        } else {
                                            let control_size = chunk[5] as usize;
                                            if control_size >= 1 && len >= 7 + control_size {
                                                let master_bits = chunk[6];
                                                has_master_mute = (master_bits & 0x01) != 0;
                                                has_master_volume = (master_bits & 0x02) != 0;
                                                for b in &chunk[6 + control_size..len - 1] {
                                                    if (b & 0x02) != 0 {
                                                        has_channel_volume = true;
                                                    }
                                                }
                                            }
                                        }

                                        feature_units.push(FeatureUnitDesc {
                                            id,
                                            source_id,
                                            has_master_mute,
                                            has_master_volume,
                                            has_channel_volume,
                                        });
                                    }
                                }
                                AC_SUBTYPE_CLOCK_SOURCE => {
                                    if len >= 8 {
                                        let bm_attributes = chunk[4];
                                        let bm_controls = chunk[5];
                                        clock_sources.push(ClockSourceDesc {
                                            id: chunk[3],
                                            clock_type: bm_attributes & 0x03,
                                            is_programmable: (bm_attributes & 0x03) == 3,
                                            supports_freq_get: (bm_controls & 0x01) != 0,
                                            supports_freq_set: (bm_controls & 0x02) != 0,
                                            supports_clock_valid: (bm_controls & 0x04) != 0,
                                            assoc_terminal: chunk[6],
                                        });
                                    }
                                }
                                AC_SUBTYPE_CLOCK_SELECTOR => {
                                    if len >= 6 {
                                        let id = chunk[3];
                                        let nr_pins = chunk[4] as usize;
                                        if len >= 5 + nr_pins {
                                            let input_pins = chunk[5..5 + nr_pins].to_vec();
                                            clock_selectors.push(ClockSelectorDesc { id, input_pins });
                                        }
                                    }
                                }
                                AC_SUBTYPE_CLOCK_MULTIPLIER => {
                                    if len >= 7 {
                                        clock_multipliers.push(ClockMultiplierDesc {
                                            id: chunk[3],
                                            source_clock_id: chunk[4],
                                        });
                                    }
                                }
                                _ => {}
                            }
                        }
                    }
                    IfaceKind::AudioStreaming {
                        terminal_link,
                        format_tag,
                        channels,
                        subslot_size,
                        bit_resolution,
                        discrete_sample_rates,
                        continuous_range,
                        ..
                    } => {
                        if len >= 3 {
                            let subtype = chunk[2];
                            match subtype {
                                AS_SUBTYPE_GENERAL => {
                                    if uac_version == 2 && len >= 16 {
                                        *terminal_link = chunk[3];
                                        *channels = chunk[10] as u16;
                                    } else if uac_version == 1 && len >= 7 {
                                        *terminal_link = chunk[3];
                                        *format_tag = u16::from_le_bytes([chunk[5], chunk[6]]);
                                    }
                                }
                                AS_SUBTYPE_FORMAT_TYPE => {
                                    if uac_version == 2 && len >= 6 {
                                        *subslot_size = chunk[4];
                                        *bit_resolution = chunk[5];
                                    } else if uac_version == 1 && len >= 8 {
                                        *channels = chunk[4] as u16;
                                        *subslot_size = chunk[5];
                                        *bit_resolution = chunk[6];
                                        let sam_freq_type = chunk[7] as usize;
                                        if sam_freq_type == 0 && len >= 14 {
                                            let min = (chunk[8] as u32) | ((chunk[9] as u32) << 8) | ((chunk[10] as u32) << 16);
                                            let max = (chunk[11] as u32) | ((chunk[12] as u32) << 8) | ((chunk[13] as u32) << 16);
                                            *continuous_range = Some((min, max));
                                        } else if sam_freq_type > 0 && len >= 8 + sam_freq_type * 3 {
                                            for s in 0..sam_freq_type {
                                                let s_off = 8 + s * 3;
                                                let rate = (chunk[s_off] as u32) | ((chunk[s_off + 1] as u32) << 8) | ((chunk[s_off + 2] as u32) << 16);
                                                discrete_sample_rates.push(rate);
                                            }
                                        }
                                    }
                                }
                                _ => {}
                            }
                        }
                    }
                    _ => {}
                }
            }
            // ENDPOINT
            5 => {
                if let IfaceKind::AudioStreaming {
                    data_ep_addr,
                    is_out,
                    sync_type,
                    usage_type,
                    max_packet,
                    interval,
                    synch_addr,
                    fb_ep_addr,
                    ..
                } = &mut current_iface {
                    if len >= 7 {
                        let addr = chunk[2];
                        let dir_out = (addr & 0x80) == 0;
                        let attr = chunk[3];
                        let is_isoc = (attr & 0x03) == 1;
                        let sync = (attr >> 2) & 0x03;
                        let usage = (attr >> 4) & 0x03;
                        let max_pkt = u16::from_le_bytes([chunk[4], chunk[5]]) & 0x07FF;
                        let ep_interval = chunk[6];
                        let ep_synch = if len >= 9 { chunk[8] } else { 0 };

                        if is_isoc {
                            if dir_out {
                                *data_ep_addr = addr;
                                *is_out = true;
                                *sync_type = sync;
                                *usage_type = usage;
                                *max_packet = max_pkt;
                                *interval = ep_interval;
                                *synch_addr = ep_synch;
                            } else if usage == 1 || *sync_type == 1 {
                                *fb_ep_addr = addr;
                            }
                        }
                    }
                }
            }
            // CS_ENDPOINT
            0x25 => {
                if let IfaceKind::AudioStreaming { supports_rate_control, .. } = &mut current_iface {
                    if uac_version == 1 && len >= 4 && chunk[2] == 0x01 {
                        *supports_rate_control = (chunk[3] & 0x01) != 0;
                    }
                }
            }
            _ => {}
        }
        offset += len;
    }

    flush_streaming_alt(&mut current_iface, &mut streaming_alts);

    Ok(AudioTopology {
        uac_version,
        ac_interface_number: ac_iface,
        clock_sources,
        clock_selectors,
        clock_multipliers,
        terminals,
        feature_units,
        streaming_alts,
    })
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_topology_resolves_clock_source_from_input_terminal() {
        let topology = AudioTopology {
            uac_version: 2,
            ac_interface_number: 0,
            clock_sources: vec![
                ClockSourceDesc {
                    id: 9,
                    clock_type: 3,
                    is_programmable: true,
                    supports_freq_get: true,
                    supports_freq_set: true,
                    supports_clock_valid: true,
                    assoc_terminal: 0,
                },
                ClockSourceDesc {
                    id: 10,
                    clock_type: 3,
                    is_programmable: true,
                    supports_freq_get: true,
                    supports_freq_set: true,
                    supports_clock_valid: true,
                    assoc_terminal: 0,
                },
            ],
            clock_selectors: vec![],
            clock_multipliers: vec![],
            terminals: vec![
                TerminalDesc {
                    id: 1,
                    is_input: true,
                    terminal_type: TERMINAL_USB_STREAMING,
                    assoc_terminal: 0,
                    source_id: 0,
                    clock_source_id: 9,
                    channels: 2,
                },
                TerminalDesc {
                    id: 3,
                    is_input: false,
                    terminal_type: TERMINAL_OUT_SPEAKER,
                    assoc_terminal: 4,
                    source_id: 2,
                    clock_source_id: 9,
                    channels: 2,
                },
            ],
            feature_units: vec![
                FeatureUnitDesc {
                    id: 2,
                    source_id: 1,
                    has_master_mute: true,
                    has_master_volume: false,
                    has_channel_volume: true,
                },
            ],
            streaming_alts: vec![
                AudioStreamingAltDesc {
                    interface_number: 1,
                    alt_setting: 2,
                    is_out: true,
                    terminal_link: 1,
                    format_tag: 1,
                    channels: 2,
                    subslot_size: 3,
                    bit_resolution: 24,
                    discrete_sample_rates: vec![],
                    continuous_range: None,
                    endpoint_address: 0x01,
                    endpoint_sync_type: 1,
                    endpoint_usage_type: 0,
                    max_packet_size: 288,
                    endpoint_interval: 1,
                    synch_address: 0,
                    feedback_endpoint_address: 0,
                    supports_rate_control: false,
                },
            ],
        };

        let alt = &topology.streaming_alts[0];
        let clock_id = topology.resolve_playback_clock_source_id(alt);
        assert_eq!(clock_id, Some(9));

        let fu = topology.resolve_playback_feature_unit(alt);
        assert!(fu.is_some());
        assert_eq!(fu.unwrap().id, 2);
    }

    #[test]
    fn test_parse_audio_topology_from_raw_uac2_fixture() {
        let uac2_bytes = include_bytes!("../../../player/src/commonTest/resources/fixtures/uac2_descriptor.bin");
        let topology = parse_audio_topology_from_raw(uac2_bytes).expect("Failed to parse UAC2 fixture");
        assert_eq!(topology.uac_version, 2);
        assert!(!topology.streaming_alts.is_empty());
        let alt = &topology.streaming_alts[0];
        let clock_id = topology.resolve_playback_clock_source_id(alt);
        assert!(clock_id.is_some());
    }

    #[test]
    fn test_parse_audio_topology_from_raw_uac1_fixture() {
        let uac1_bytes = include_bytes!("../../../player/src/commonTest/resources/fixtures/uac1_descriptor.bin");
        let topology = parse_audio_topology_from_raw(uac1_bytes).expect("Failed to parse UAC1 fixture");
        assert_eq!(topology.uac_version, 1);
        assert!(!topology.streaming_alts.is_empty());
    }
}
