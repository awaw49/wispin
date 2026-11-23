package com.iap2loc.android.protocol;

/** Builds IdentificationInformation (0x1D01) for a plain MFi GPS accessory. */
public final class Identification {
    private static final int[] CORE_SENT = {
            0xAA01, // AuthenticationCertificate
            0xAA03, // AuthenticationResponse
            0xFFFB, // LocationInformation
    };
    private static final int[] CORE_RECEIVED = {
            0xAA00, 0xAA02, 0xAA04, 0xAA05, 0xFFFA, 0xFFFC,
    };

    private Identification() {}

    public static byte[] build(AccessoryProfile identity, byte[] bluetoothAdapter) {
        Csm.Params parameters = new Csm.Params()
                .str(0, identity.name)
                .str(1, identity.model)
                .str(2, identity.manufacturer)
                .str(3, identity.serial)
                .str(4, identity.firmware)
                .str(5, identity.hardware)
                .u16List(6, CORE_SENT)
                .u16List(7, CORE_RECEIVED)
                .u8(8, 0)
                .u16(9, 20)
                .str(12, "en")
                .str(13, "en");

        // Parameter 17 is mandatory; without it iOS rejects the accessory with 0x1D03 (offending 17).
        if (bluetoothAdapter != null && bluetoothAdapter.length == 6) {
            final byte[] adapter = bluetoothAdapter;
            parameters.group(17, transport -> transport
                    .u16(0, 0)
                    .str(1, "blue")
                    .empty(2)
                    .param(3, adapter)
                    .str(4, "blue")
                    .empty(5));
        }

        parameters.group(22, component -> component
                .u16(0, 0)
                .str(1, identity.name)
                .empty(17)
                .empty(18));

        return parameters.toByteArray();
    }
}
