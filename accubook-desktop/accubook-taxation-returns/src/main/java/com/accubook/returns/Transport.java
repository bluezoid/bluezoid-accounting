package com.accubook.returns;

import java.time.LocalDate;

/**
 * How goods travel, for an e-way bill.
 *
 * @param mode           1 road, 2 rail, 3 air, 4 ship
 * @param distanceKm     0 to 4000; 0 lets the portal work it out from the PIN codes
 * @param transporterId  the transporter's GSTIN or 15-character ID, or null
 * @param vehicleNo      for road, e.g. MH12AB1234; may follow later (part B)
 * @param vehicleType    R regular, O over-dimensional cargo
 * @param transDocNo     lorry receipt, railway receipt or airway bill number, or null
 */
public record Transport(int mode, int distanceKm, String transporterId, String transporterName, String vehicleNo,
                        String vehicleType, String transDocNo, LocalDate transDocDate) {

    public Transport {
        if (mode < 1 || mode > 4) {
            throw new ReturnException("Transport mode is 1 road, 2 rail, 3 air or 4 ship");
        }
        if (distanceKm < 0 || distanceKm > 4000) {
            throw new ReturnException("The distance is 0 to 4000 km");
        }
        if (vehicleNo != null) {
            vehicleNo = vehicleNo.replaceAll("[\\s-]", "").toUpperCase(java.util.Locale.ROOT);
            if (!vehicleNo.matches("[A-Z0-9]{4,15}")) {
                throw new ReturnException("\"" + vehicleNo + "\" is not a vehicle number");
            }
        }
        vehicleType = vehicleType == null ? "R" : vehicleType;
    }

    /** By road, in a regular vehicle. */
    public static Transport road(int distanceKm, String vehicleNo) {
        return new Transport(1, distanceKm, null, null, vehicleNo, "R", null, null);
    }
}
