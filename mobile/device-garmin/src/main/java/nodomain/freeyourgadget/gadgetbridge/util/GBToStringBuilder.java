package nodomain.freeyourgadget.gadgetbridge.util;

import org.apache.commons.lang3.builder.ToStringBuilder;
import org.apache.commons.lang3.builder.ToStringStyle;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Date;
import java.util.Locale;

public class GBToStringBuilder extends ToStringBuilder {
    public static final GBToStringStyle STYLE = new GBToStringStyle();

    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS", Locale.ROOT);

    public GBToStringBuilder(final Object object) {
        super(object, STYLE);
    }

    public static class GBToStringStyle extends ToStringStyle {
        public GBToStringStyle() {
            super();

            this.setUseShortClassName(true);
            this.setUseIdentityHashCode(false);

            this.setContentStart("{");
            this.setContentEnd("}");

            this.setArrayStart("[");
            this.setArrayEnd("]");

            this.setFieldSeparator(", ");
            this.setFieldNameValueSeparator("=");

            this.setNullText("null");
        }

        @Override
        public void append(final StringBuffer buffer, final String fieldName, final Object value, final Boolean fullDetail) {
            // omit nulls
            if (value != null) {
                if (value instanceof Date) {
                    final String formatted = DATE_FORMATTER.format(((Date) value).toInstant().atZone(ZoneId.systemDefault()));
                    super.append(buffer, fieldName, formatted, fullDetail);
                } else {
                    super.append(buffer, fieldName, value, fullDetail);
                }
            }
        }
    }
}
