package tech.idct.clipra.core.engine.render

import java.io.ByteArrayOutputStream

/**
 * A blank H.264 stream written directly (#227), for the preview's canvas clips: constrained
 * baseline, [SIDE] × [SIDE], one IDR picture a second and P pictures between them whose
 * macroblocks are all skipped. Each picture is a few bytes, so a clip of many minutes is made
 * in a moment, without an encoder.
 *
 * The IDR picture is mid-grey (every macroblock Intra 16×16 with DC prediction and no
 * residual), and each P picture repeats the one before. Nothing shows it: the compositor draws
 * every pixel of the preview itself and only takes the canvas's timing.
 *
 * NAL units are returned without start codes, after emulation prevention.
 */
internal object BlankAvc {
    const val SIDE = 128
    private const val MBS_PER_SIDE = SIDE / 16
    private const val MBS = MBS_PER_SIDE * MBS_PER_SIDE

    /** frame_num has 8 bits: a group of pictures (a second, at most 60) stays below 256. */
    private const val LOG2_MAX_FRAME_NUM = 8

    /** Level 3.0, as common as levels get; the stream needs far less. */
    private const val LEVEL = 30

    private const val NAL_SLICE = 1
    private const val NAL_IDR = 5
    private const val NAL_SPS = 7
    private const val NAL_PPS = 8
    private const val SLICE_P = 5
    private const val SLICE_I = 7

    /** I_16x16_2_0_0: DC prediction, no coded luma or chroma (Table 7-11). */
    private const val MB_TYPE_I16X16_DC = 3

    val sps: ByteArray = nal(3, NAL_SPS) {
        bits(66, 8) // profile_idc: baseline
        bits(0b11000000, 8) // constraint_set0 and set1 (constrained baseline), reserved
        bits(LEVEL, 8)
        ue(0) // seq_parameter_set_id
        ue(LOG2_MAX_FRAME_NUM - 4)
        ue(2) // pic_order_cnt_type: in decoding order
        ue(1) // max_num_ref_frames
        bit(0) // gaps_in_frame_num_value_allowed_flag
        ue(MBS_PER_SIDE - 1) // pic_width_in_mbs_minus1
        ue(MBS_PER_SIDE - 1) // pic_height_in_map_units_minus1
        bit(1) // frame_mbs_only_flag
        bit(1) // direct_8x8_inference_flag
        bit(0) // frame_cropping_flag
        bit(1) // vui_parameters_present_flag
        // VUI: nothing but the bitstream restriction, which lets a decoder show each picture
        // as soon as it is decoded.
        repeat(8) { bit(0) } // aspect ratio ... pic_struct_present_flag
        bit(1) // bitstream_restriction_flag
        bit(1) // motion_vectors_over_pic_boundaries_flag
        ue(0) // max_bytes_per_pic_denom
        ue(0) // max_bits_per_mb_denom
        ue(15) // log2_max_mv_length_horizontal
        ue(15) // log2_max_mv_length_vertical
        ue(0) // max_num_reorder_frames
        ue(1) // max_dec_frame_buffering
    }

    val pps: ByteArray = nal(3, NAL_PPS) {
        ue(0) // pic_parameter_set_id
        ue(0) // seq_parameter_set_id
        bit(0) // entropy_coding_mode_flag: CAVLC
        bit(0) // bottom_field_pic_order_in_frame_present_flag
        ue(0) // num_slice_groups_minus1
        ue(0) // num_ref_idx_l0_default_active_minus1
        ue(0) // num_ref_idx_l1_default_active_minus1
        bit(0) // weighted_pred_flag
        bits(0, 2) // weighted_bipred_idc
        se(0) // pic_init_qp_minus26
        se(0) // pic_init_qs_minus26
        se(0) // chroma_qp_index_offset
        bit(1) // deblocking_filter_control_present_flag
        bit(0) // constrained_intra_pred_flag
        bit(0) // redundant_pic_cnt_present_flag
    }

    /** The IDR picture that starts a group; [idrPicId] differs from the group's before. */
    fun idr(idrPicId: Int): ByteArray = nal(3, NAL_IDR) {
        ue(0) // first_mb_in_slice
        ue(SLICE_I)
        ue(0) // pic_parameter_set_id
        bits(0, LOG2_MAX_FRAME_NUM) // frame_num
        ue(idrPicId)
        bit(0) // no_output_of_prior_pics_flag
        bit(0) // long_term_reference_flag
        se(0) // slice_qp_delta
        ue(1) // disable_deblocking_filter_idc
        repeat(MBS) {
            ue(MB_TYPE_I16X16_DC)
            ue(0) // intra_chroma_pred_mode: DC
            se(0) // mb_qp_delta
            bit(1) // coeff_token of the luma DC block: no coefficients (nC 0)
        }
    }

    /** The P picture [frameNum] pictures after its group's IDR, every macroblock skipped. */
    fun skipped(frameNum: Int): ByteArray {
        require(frameNum in 1 until (1 shl LOG2_MAX_FRAME_NUM)) { "frame_num $frameNum" }
        return nal(2, NAL_SLICE) {
            ue(0) // first_mb_in_slice
            ue(SLICE_P)
            ue(0) // pic_parameter_set_id
            bits(frameNum, LOG2_MAX_FRAME_NUM)
            bit(0) // num_ref_idx_active_override_flag
            bit(0) // ref_pic_list_modification_flag_l0
            bit(0) // adaptive_ref_pic_marking_mode_flag
            se(0) // slice_qp_delta
            ue(1) // disable_deblocking_filter_idc
            ue(MBS) // mb_skip_run: the whole picture
        }
    }

    private fun nal(refIdc: Int, type: Int, write: BitWriter.() -> Unit): ByteArray {
        val rbsp = BitWriter().apply(write).trailingBits()
        val out = ByteArrayOutputStream(rbsp.size + 4)
        out.write((refIdc shl 5) or type)
        // Emulation prevention: no 00 00 followed by 00 to 03 inside a NAL unit.
        var zeros = 0
        for (byte in rbsp) {
            val value = byte.toInt() and 0xFF
            if (zeros >= 2 && value <= 3) {
                out.write(3)
                zeros = 0
            }
            out.write(value)
            zeros = if (value == 0) zeros + 1 else 0
        }
        return out.toByteArray()
    }

    /** Writes bits most significant first, with Exp-Golomb codes (H.264 section 9.1). */
    internal class BitWriter {
        private val out = ByteArrayOutputStream()
        private var current = 0
        private var count = 0

        fun bit(value: Int) {
            current = (current shl 1) or (value and 1)
            if (++count == 8) {
                out.write(current)
                current = 0
                count = 0
            }
        }

        fun bits(value: Int, length: Int) {
            for (i in length - 1 downTo 0) bit(value ushr i)
        }

        fun ue(value: Int) {
            require(value >= 0) { "ue($value)" }
            val code = value + 1
            val length = 32 - Integer.numberOfLeadingZeros(code)
            bits(0, length - 1)
            bits(code, length)
        }

        fun se(value: Int) = ue(if (value > 0) 2 * value - 1 else -2 * value)

        /** rbsp_trailing_bits: a one, then zeros to the byte. */
        fun trailingBits(): ByteArray {
            bit(1)
            while (count != 0) bit(0)
            return out.toByteArray()
        }
    }
}
