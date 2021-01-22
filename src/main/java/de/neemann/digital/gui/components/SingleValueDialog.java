/*
 * Copyright (c) 2016 Helmut Neemann, Rüdiger Heintz
 * Use of this source code is governed by the GPL v3 license
 * that can be found in the LICENSE file.
 */
package de.neemann.digital.gui.components;

import de.neemann.digital.core.*;
import de.neemann.digital.lang.Lang;
import de.neemann.gui.Screen;

import javax.swing.*;
import javax.swing.event.ChangeListener;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.ArrayList;

import static de.neemann.digital.core.IntFormat.*;

/**
 * Dialog to edit a single value.
 * Used to enter a multi bit input value.
 */
public final class SingleValueDialog extends JDialog implements ModelStateObserverTyped {

    private static final Format[] FORMATS;

    static {
        ArrayList<Format> f = new ArrayList<>();
        for (IntFormat intf : VALUES) {
            if (!(intf instanceof IntFormatFixedPoint))
                f.add(new Format(intf));
        }
        FORMATS = f.toArray(new Format[]{});
    }

    private static class Format {
        private final IntFormat intFormat;
        private final String name;

        public Format(IntFormat intFormat) {
            this.intFormat = intFormat;
            name = Lang.get("key_intFormat_" + intFormat.getName());
        }

        @Override
        public String toString() {
            return name;
        }
    }

    private static Format findFormat(IntFormat f) {
        for (Format ff : FORMATS)
            if (ff.intFormat.equals(f))
                return ff;
        return null;
    }

    private final ObservableValue value;
    private final SyncAccess syncAccess;

    private final JTextField textField;
    private boolean textIsModifying;
    private final boolean supportsHighZ;
    private final JComboBox<Format> formatComboBox;
    private final long mask;
    private JCheckBox[] checkBoxes;
    private Value editValue;
    private IntFormat intFormat = DEF;

    /**
     * Edits a single value
     *
     * @param parent        the parent frame
     * @param pos           the position to pop up the dialog
     * @param label         the name of the value
     * @param value         the value to edit
     * @param supportsHighZ true is high z is supported
     * @param model         the model
     */
    public SingleValueDialog(JFrame parent, Point pos, String label, ObservableValue value, boolean supportsHighZ, Model model) {
        super(parent, Lang.get("win_valueInputTitle_N", label), false);
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
        this.value = value;
        this.syncAccess = model;

        editValue = value.getCopy();
        this.supportsHighZ = supportsHighZ;
        mask = Bits.mask(value.getBits());

        textField = new JTextField(10);
        textField.setHorizontalAlignment(JTextField.RIGHT);

        formatComboBox = new JComboBox<>(FORMATS);
        formatComboBox.addActionListener(actionEvent -> {
            Format selectedItem = (Format) formatComboBox.getSelectedItem();
            if (selectedItem != null)
                intFormat = selectedItem.intFormat;
            setLongToDialog(editValue);
        });

        model.modify(() -> model.addObserver(this));
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosed(WindowEvent windowEvent) {
                model.modify(() -> model.removeObserver(SingleValueDialog.this));
            }
        });

        JPanel panel = new JPanel(new GridBagLayout());
        ConstraintsBuilder constr = new ConstraintsBuilder().inset(3).fill();
        panel.add(formatComboBox, constr);
        JSpinner spinner = new JSpinner(new MySpinnerModel()) {
            @Override
            protected JComponent createEditor(SpinnerModel spinnerModel) {
                return textField;
            }
        };
        panel.add(spinner, constr.dynamicWidth().x(1));
        constr.nextRow();
        panel.add(new JLabel(Lang.get("key_intFormat_bin")), constr);
        panel.add(createCheckBoxPanel(editValue), constr.dynamicWidth().x(1));
        getContentPane().add(panel);

        textField.getDocument().addDocumentListener(new MyDocumentListener(() -> setStringToDialog(textField.getText())));

        setLongToDialog(editValue);

        JButton okButton = new JButton(new AbstractAction(Lang.get("ok")) {
            @Override
            public void actionPerformed(ActionEvent actionEvent) {
                apply();
                dispose();
            }
        });
        final AbstractAction applyAction = new AbstractAction(Lang.get("btn_apply")) {
            @Override
            public void actionPerformed(ActionEvent actionEvent) {
                apply();
            }
        };
        JButton applyButton = new JButton(applyAction);
        textField.getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, KeyEvent.SHIFT_DOWN_MASK, true), applyAction);
        textField.getActionMap().put(applyAction, applyAction);

        JPanel buttonPanel = new JPanel(new GridLayout(2, 1));
        buttonPanel.add(okButton);
        buttonPanel.add(applyButton);
        getContentPane().add(buttonPanel, BorderLayout.EAST);

        getRootPane().setDefaultButton(okButton);
        getRootPane().registerKeyboardAction(actionEvent -> dispose(),
                KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0),
                JComponent.WHEN_IN_FOCUSED_WINDOW);

        pack();
        Screen.setLocation(this, pos, true);
        textField.requestFocus();
        textField.select(0, Integer.MAX_VALUE);
    }

    @Override
    public void requestFocus() {
        super.requestFocus();
        textField.requestFocus();
        textField.select(0, Integer.MAX_VALUE);
    }

    private void apply() {
        syncAccess.modify(() -> editValue.applyTo(value));
    }

    @Override
    public void handleEvent(ModelEvent event) {
        if (event.equals(ModelEvent.CLOSED))
            dispose();
    }

    @Override
    public ModelEventType[] getEvents() {
        return new ModelEventType[]{ModelEventType.CLOSED};
    }

    private JPanel createCheckBoxPanel(Value value) {
        JPanel p = new JPanel();
        p.setLayout(new BoxLayout(p, BoxLayout.X_AXIS));
        int bits = value.getBits();
        long l = value.getValue();
        checkBoxes = new JCheckBox[bits];
        for (int i = bits - 1; i >= 0; i--) {
            final int bit = i;
            checkBoxes[bit] = new JCheckBox("", (l & (1L << bit)) != 0);
            checkBoxes[bit].setBorder(null);
            checkBoxes[bit].addActionListener(actionEvent -> setBit(bit, checkBoxes[bit].isSelected()));
            p.add(checkBoxes[bit]);
        }
        return p;
    }

    private void setBit(int bitNum, boolean set) {
        if (set)
            editValue = new Value(editValue.getValue() | 1L << bitNum, editValue.getBits());
        else
            editValue = new Value(editValue.getValue() & ~(1L << bitNum), editValue.getBits());

        setLongToDialog(editValue);
    }

    private void setLongToDialog(Value editValue) {
        if (!textIsModifying) {
            textField.setText(intFormat.formatToEdit(editValue));
            textField.requestFocus();
        }
    }

    /**
     * Sets the selected format
     *
     * @param format the format
     * @return this for chained calls
     */
    public SingleValueDialog setSelectedFormat(IntFormat format) {
        intFormat = format;
        formatComboBox.setSelectedItem(findFormat(intFormat));
        setLongToDialog(editValue);
        requestFocus();
        return this;
    }

    private void setStringToDialog(String text) {
        text = text.trim();
        if (text.equalsIgnoreCase("z") && supportsHighZ)
            editValue = new Value(editValue.getBits());
        else {
            try {
                editValue = new Value(Bits.decode(text), editValue.getBits());
            } catch (Bits.NumberFormatException e) {
                // do nothing on error
            }
        }
        long value = editValue.getValue();
        for (int i = 0; i < checkBoxes.length; i++) {
            checkBoxes[i].setSelected((value & (1L << i)) != 0);
        }
    }

    private final class MyDocumentListener implements DocumentListener {
        private final Runnable runnable;

        private MyDocumentListener(Runnable runnable) {
            this.runnable = runnable;
        }

        @Override
        public void insertUpdate(DocumentEvent documentEvent) {
            run();
        }

        @Override
        public void removeUpdate(DocumentEvent documentEvent) {
            run();
        }

        @Override
        public void changedUpdate(DocumentEvent documentEvent) {
            run();
        }

        private void run() {
            textIsModifying = true;
            runnable.run();
            textIsModifying = false;
        }
    }

    private class MySpinnerModel implements SpinnerModel {
        @Override
        public Object getValue() {
            return editValue;
        }

        @Override
        public void setValue(Object o) {
            if (o instanceof Number) {
                editValue = new Value(((Number) o).longValue(), editValue.getBits());
                setLongToDialog(editValue);
                apply();
            }
        }

        @Override
        public Object getNextValue() {
            return (editValue.getValue() + 1) & mask;
        }

        @Override
        public Object getPreviousValue() {
            return (editValue.getValue() - 1) & mask;
        }

        @Override
        public void addChangeListener(ChangeListener changeListener) {
        }

        @Override
        public void removeChangeListener(ChangeListener changeListener) {
        }
    }
}
