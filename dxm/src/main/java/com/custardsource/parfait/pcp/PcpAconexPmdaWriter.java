package com.custardsource.parfait.pcp;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.util.Collection;

import com.custardsource.parfait.pcp.types.AbstractTypeHandler;
import com.custardsource.parfait.pcp.types.MmvMetricType;

public class PcpAconexPmdaWriter extends BasePcpWriter {
    public static final String ENCODING = "ISO-8859-1";
    public static final Charset CHARSET = Charset.forName(ENCODING);
    public static final byte PROTOCOL_VERSION = 1;
    public static final int METRIC_NAME_LIMIT = 200;
    public static final int MAX_STRING_LENGTH = 256;
	private static final int HEADER_LENGTH = 9; // 8 for version, 1 for protocol version
	private final File headerFile;
    // @GuardedBy(this)
	private int nextDataValueOffset = HEADER_LENGTH;  

	public PcpAconexPmdaWriter(File headerFile, File dataFile) {
		super(dataFile);
		this.headerFile = headerFile;
		registerType(String.class, new AbstractTypeHandler<String>(
				MmvMetricType.STRING, MAX_STRING_LENGTH) {
			public void putBytes(ByteBuffer buffer, String value) {
				byte[] stringData = value.getBytes(CHARSET);
				int length = Math.min(stringData.length, MAX_STRING_LENGTH - 1);
				buffer.put(stringData, 0, length);
				buffer.put((byte) 0);
			}
		});
	}

    private void writeHeaderValue(Writer output, String name, String value) throws IOException {
        output.append(name);
        output.append('=');
        output.append(value);
        output.append("\n");
    }

	@Override
	protected Charset getCharset() {
		return CHARSET;
	}

	@Override
	protected int getFileLength(Collection<PcpMetricInfo> infos) {
		PcpMetricInfo lastInfo = null;
		for (PcpMetricInfo info : infos) {
			lastInfo = info;
		}
		return lastInfo.getOffsets().dataValueOffset()
				+ lastInfo.getTypeHandler().getDataLength();
	}

    private int align(int offset, int dataSize) {
        int alignmentBoundry = dataSize == 8 ? 8 : 4;
        if (offset % alignmentBoundry != 0) {
            return ((offset / alignmentBoundry) + 1) * alignmentBoundry;
        } else {
            return offset;
        }
    }

	@Override
	protected int getMetricNameLimit() {
		return METRIC_NAME_LIMIT;
	}

	@Override
	protected synchronized PcpOffset getNextOffsets(PcpMetricInfo currentInfo,
			int totalMetrics) {
		// Descriptor offsets not used by this file format; block + value
		// offsets are the same as there's no metadata
		nextDataValueOffset = align(nextDataValueOffset, currentInfo
				.getTypeHandler().getDataLength());
		PcpOffset offset = new PcpOffset(0, nextDataValueOffset,
				nextDataValueOffset);
		nextDataValueOffset += currentInfo.getTypeHandler().getDataLength();
		return offset;
	}

	@Override
	protected void populateDataBuffer(ByteBuffer dataFileBuffer,
			Collection<PcpMetricInfo> metricInfos) throws IOException {
		long fileGeneration = System.currentTimeMillis();
		// Need to write header here too
		OutputStreamWriter headerWriter = new OutputStreamWriter(
				new BufferedOutputStream(new FileOutputStream(headerFile)),
				ENCODING);
		writeHeaderValue(headerWriter, "version", String
				.valueOf(PROTOCOL_VERSION));
		writeHeaderValue(headerWriter, "generation", String
				.valueOf(fileGeneration));
		for (PcpMetricInfo metricInfo : metricInfos) {
			writeHeaderValue(headerWriter, metricInfo.getMetricName(),
					metricInfo.getOffsets().dataValueOffset()
							+ ","
							+ metricInfo.getTypeHandler().getMetricType()
									.getDescription());
		}
        headerWriter.close();
        
		for (PcpMetricInfo metricInfo : metricInfos) {
			updateValue(metricInfo, metricInfo.getInitialValue());
		}
        
        // And finally update the data file generation number to match the header file
        dataFileBuffer.put(8, PROTOCOL_VERSION);
        dataFileBuffer.putLong(0, fileGeneration);

        
	}
}