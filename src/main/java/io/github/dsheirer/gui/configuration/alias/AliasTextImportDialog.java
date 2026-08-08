/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * ****************************************************************************
 */

package io.github.dsheirer.gui.configuration.alias;

import io.github.dsheirer.alias.Alias;
import io.github.dsheirer.alias.AliasListDefinition;
import io.github.dsheirer.alias.AliasTextImportParser;
import io.github.dsheirer.alias.id.priority.Priority;
import io.github.dsheirer.alias.id.talkgroup.Talkgroup;
import io.github.dsheirer.alias.id.talkgroup.TalkgroupFormat;
import io.github.dsheirer.configuration.ConfigurationManager;
import io.github.dsheirer.protocol.Protocol;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.collections.FXCollections;
import javafx.geometry.HPos;
import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.CheckMenuItem;
import javafx.scene.control.ColorPicker;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.MenuButton;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.stage.FileChooser;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.Window;
import org.controlsfx.control.ToggleSwitch;

/**
 * Modal dialog that imports talkgroup aliases from pasted columnar text (TGID / Alias / Description) and applies
 * shared settings (group, color, listen, record, and streaming) to every imported alias.
 */
public class AliasTextImportDialog extends Stage
{
    private final ConfigurationManager mConfigurationManager;
    private AliasTextImportParser.Result mParseResult = AliasTextImportParser.parse(null);
    private TextArea mTextArea;
    private Label mSummaryLabel;
    private TableView<AliasTextImportParser.ParsedTalkgroup> mPreviewTable;
    private ComboBox<String> mAliasListComboBox;
    private TextField mGroupField;
    private CheckBox mColorCheckBox;
    private ColorPicker mColorPicker;
    private ToggleSwitch mMonitorToggle;
    private ComboBox<Integer> mPriorityComboBox;
    private ToggleSwitch mRecordToggle;
    private MenuButton mStreamsMenuButton;
    private CheckBox mSkipExistingCheckBox;
    private Button mImportButton;

    /**
     * Constructs an instance.
     *
     * @param configurationManager for alias, icon and streaming configuration access
     * @param initialAliasListName to preselect, or null
     * @param owner window for modality
     */
    public AliasTextImportDialog(ConfigurationManager configurationManager, String initialAliasListName, Window owner)
    {
        mConfigurationManager = configurationManager;

        setTitle("Import Aliases From Text");
        initModality(Modality.APPLICATION_MODAL);

        if(owner != null)
        {
            initOwner(owner);
        }

        VBox content = new VBox();
        content.setPadding(new Insets(10));
        content.setSpacing(10);

        Label instructions = new Label("Paste talkgroup text below with columns: TGID, Alias, Description. " +
            "Columns can be separated by tabs, two or more spaces, or CSV formatted such as " +
            "\"TGID\",\"Alias\",\"Description\". Lines without a leading talkgroup number are ignored. " +
            "When a line has no alias, the description is used as the alias.");
        instructions.setWrapText(true);

        HBox textHeader = new HBox();
        textHeader.setSpacing(10);
        Button loadFileButton = new Button("Load File...");
        loadFileButton.setOnAction(event -> loadFile());
        textHeader.getChildren().addAll(loadFileButton, getSummaryLabel());

        VBox.setVgrow(getTextArea(), javafx.scene.layout.Priority.ALWAYS);
        VBox.setVgrow(getPreviewTable(), javafx.scene.layout.Priority.ALWAYS);

        HBox buttons = new HBox();
        buttons.setSpacing(10);
        Button cancelButton = new Button("Cancel");
        cancelButton.setOnAction(event -> close());
        buttons.getChildren().addAll(getImportButton(), cancelButton);

        content.getChildren().addAll(instructions, textHeader, getTextArea(), getPreviewTable(),
            getSettingsPane(), buttons);

        if(initialAliasListName != null)
        {
            getAliasListComboBox().getSelectionModel().select(initialAliasListName);
        }
        else if(!getAliasListComboBox().getItems().isEmpty())
        {
            getAliasListComboBox().getSelectionModel().select(0);
        }

        updatePreview();
        setScene(new Scene(content, 760, 720));
    }

    private TextArea getTextArea()
    {
        if(mTextArea == null)
        {
            mTextArea = new TextArea();
            mTextArea.setPromptText("10301\t1 STATE\tAdmin - State Operations");
            mTextArea.setPrefRowCount(10);
            mTextArea.textProperty().addListener((observable, oldValue, newValue) -> updatePreview());
        }

        return mTextArea;
    }

    private Label getSummaryLabel()
    {
        if(mSummaryLabel == null)
        {
            mSummaryLabel = new Label("No talkgroups parsed");
        }

        return mSummaryLabel;
    }

    private TableView<AliasTextImportParser.ParsedTalkgroup> getPreviewTable()
    {
        if(mPreviewTable == null)
        {
            mPreviewTable = new TableView<>();
            mPreviewTable.setPlaceholder(new Label("Parsed talkgroups will preview here"));
            mPreviewTable.setPrefHeight(200);

            TableColumn<AliasTextImportParser.ParsedTalkgroup,Integer> talkgroupColumn = new TableColumn<>("TGID");
            talkgroupColumn.setPrefWidth(90);
            talkgroupColumn.setCellValueFactory(features ->
                new ReadOnlyObjectWrapper<>(features.getValue().talkgroup()));

            TableColumn<AliasTextImportParser.ParsedTalkgroup,String> aliasColumn = new TableColumn<>("Alias");
            aliasColumn.setPrefWidth(220);
            aliasColumn.setCellValueFactory(features -> new ReadOnlyStringWrapper(features.getValue().alias()));

            TableColumn<AliasTextImportParser.ParsedTalkgroup,String> descriptionColumn =
                new TableColumn<>("Description");
            descriptionColumn.setPrefWidth(400);
            descriptionColumn.setCellValueFactory(features ->
                new ReadOnlyStringWrapper(features.getValue().description()));

            mPreviewTable.getColumns().add(talkgroupColumn);
            mPreviewTable.getColumns().add(aliasColumn);
            mPreviewTable.getColumns().add(descriptionColumn);
        }

        return mPreviewTable;
    }

    private GridPane getSettingsPane()
    {
        GridPane gridPane = new GridPane();
        gridPane.setHgap(10);
        gridPane.setVgap(10);

        int row = 0;

        Label settingsLabel = new Label("Settings applied to every imported alias:");
        GridPane.setConstraints(settingsLabel, 0, row, 4, 1);
        gridPane.getChildren().add(settingsLabel);

        Label listLabel = new Label("Alias List");
        GridPane.setHalignment(listLabel, HPos.RIGHT);
        GridPane.setConstraints(listLabel, 0, ++row);
        gridPane.getChildren().add(listLabel);

        GridPane.setConstraints(getAliasListComboBox(), 1, row);
        gridPane.getChildren().add(getAliasListComboBox());

        Label groupLabel = new Label("Group");
        GridPane.setHalignment(groupLabel, HPos.RIGHT);
        GridPane.setConstraints(groupLabel, 2, row);
        gridPane.getChildren().add(groupLabel);

        GridPane.setConstraints(getGroupField(), 3, row);
        gridPane.getChildren().add(getGroupField());

        Label colorLabel = new Label("Color");
        GridPane.setHalignment(colorLabel, HPos.RIGHT);
        GridPane.setConstraints(colorLabel, 0, ++row);
        gridPane.getChildren().add(colorLabel);

        HBox colorBox = new HBox();
        colorBox.setSpacing(10);
        colorBox.getChildren().addAll(getColorCheckBox(), getColorPicker());
        GridPane.setConstraints(colorBox, 1, row);
        gridPane.getChildren().add(colorBox);

        Label streamLabel = new Label("Stream");
        GridPane.setHalignment(streamLabel, HPos.RIGHT);
        GridPane.setConstraints(streamLabel, 2, row);
        gridPane.getChildren().add(streamLabel);

        GridPane.setConstraints(getStreamsMenuButton(), 3, row);
        gridPane.getChildren().add(getStreamsMenuButton());

        Label listenLabel = new Label("Listen");
        GridPane.setHalignment(listenLabel, HPos.RIGHT);
        GridPane.setConstraints(listenLabel, 0, ++row);
        gridPane.getChildren().add(listenLabel);

        HBox listenBox = new HBox();
        listenBox.setSpacing(10);
        Label priorityLabel = new Label("Priority");
        listenBox.getChildren().addAll(getMonitorToggle(), priorityLabel, getPriorityComboBox());
        GridPane.setConstraints(listenBox, 1, row);
        gridPane.getChildren().add(listenBox);

        Label recordLabel = new Label("Record");
        GridPane.setHalignment(recordLabel, HPos.RIGHT);
        GridPane.setConstraints(recordLabel, 2, row);
        gridPane.getChildren().add(recordLabel);

        GridPane.setConstraints(getRecordToggle(), 3, row);
        gridPane.getChildren().add(getRecordToggle());

        GridPane.setConstraints(getSkipExistingCheckBox(), 1, ++row, 3, 1);
        gridPane.getChildren().add(getSkipExistingCheckBox());

        return gridPane;
    }

    private ComboBox<String> getAliasListComboBox()
    {
        if(mAliasListComboBox == null)
        {
            mAliasListComboBox = new ComboBox<>(FXCollections.observableArrayList(
                mConfigurationManager.getAliasModel().aliasListNames()));
            mAliasListComboBox.getSelectionModel().selectedItemProperty()
                .addListener((observable, oldValue, newValue) -> updatePreview());
        }

        return mAliasListComboBox;
    }

    private TextField getGroupField()
    {
        if(mGroupField == null)
        {
            mGroupField = new TextField();
            mGroupField.setPromptText("(no group)");
        }

        return mGroupField;
    }

    private CheckBox getColorCheckBox()
    {
        if(mColorCheckBox == null)
        {
            mColorCheckBox = new CheckBox("Set");
        }

        return mColorCheckBox;
    }

    private ColorPicker getColorPicker()
    {
        if(mColorPicker == null)
        {
            mColorPicker = new ColorPicker(Color.BLACK);
            mColorPicker.setStyle("-fx-color-rect-width: 60px; -fx-color-label-visible: false;");
            mColorPicker.disableProperty().bind(getColorCheckBox().selectedProperty().not());
        }

        return mColorPicker;
    }

    private ToggleSwitch getMonitorToggle()
    {
        if(mMonitorToggle == null)
        {
            mMonitorToggle = new ToggleSwitch();
            mMonitorToggle.setSelected(true);
        }

        return mMonitorToggle;
    }

    private ComboBox<Integer> getPriorityComboBox()
    {
        if(mPriorityComboBox == null)
        {
            mPriorityComboBox = new ComboBox<>();
            mPriorityComboBox.getItems().add(null);

            for(int priority = Priority.MIN_PRIORITY; priority < Priority.MAX_PRIORITY; priority++)
            {
                mPriorityComboBox.getItems().add(priority);
            }

            mPriorityComboBox.setPromptText("Default");
            mPriorityComboBox.disableProperty().bind(getMonitorToggle().selectedProperty().not());
        }

        return mPriorityComboBox;
    }

    private ToggleSwitch getRecordToggle()
    {
        if(mRecordToggle == null)
        {
            mRecordToggle = new ToggleSwitch();
        }

        return mRecordToggle;
    }

    private MenuButton getStreamsMenuButton()
    {
        if(mStreamsMenuButton == null)
        {
            mStreamsMenuButton = new MenuButton("Streams");
            List<String> streams = mConfigurationManager.getBroadcastModel().getBroadcastConfigurationNames();

            if(streams.isEmpty())
            {
                CheckMenuItem none = new CheckMenuItem("No streams configured");
                none.setDisable(true);
                mStreamsMenuButton.getItems().add(none);
            }
            else
            {
                for(String stream: streams)
                {
                    mStreamsMenuButton.getItems().add(new CheckMenuItem(stream));
                }
            }
        }

        return mStreamsMenuButton;
    }

    private CheckBox getSkipExistingCheckBox()
    {
        if(mSkipExistingCheckBox == null)
        {
            mSkipExistingCheckBox = new CheckBox("Skip talkgroups that already have an alias in this list");
            mSkipExistingCheckBox.setSelected(true);
        }

        return mSkipExistingCheckBox;
    }

    private Button getImportButton()
    {
        if(mImportButton == null)
        {
            mImportButton = new Button("Import");
            mImportButton.setDisable(true);
            mImportButton.setOnAction(event -> importAliases());
        }

        return mImportButton;
    }

    private void loadFile()
    {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Load Alias Text File");
        chooser.getExtensionFilters().addAll(new FileChooser.ExtensionFilter("Text Files", "*.txt", "*.csv", "*.tsv"),
            new FileChooser.ExtensionFilter("All Files", "*.*"));
        java.io.File selected = chooser.showOpenDialog(getScene().getWindow());

        if(selected != null)
        {
            try
            {
                getTextArea().setText(Files.readString(selected.toPath(), StandardCharsets.UTF_8));
            }
            catch(IOException e)
            {
                Alert alert = new Alert(Alert.AlertType.ERROR, "Unable to read the file: " + e.getMessage(),
                    ButtonType.OK);
                alert.setTitle("Load Alias Text File");
                alert.initOwner(this);
                alert.showAndWait();
            }
        }
    }

    private void updatePreview()
    {
        mParseResult = AliasTextImportParser.parse(getTextArea().getText());
        getPreviewTable().getItems().setAll(mParseResult.talkgroups());
        StringBuilder summary = new StringBuilder();
        summary.append(mParseResult.talkgroups().size()).append(" talkgroups parsed");

        if(mParseResult.ignoredLineCount() > 0)
        {
            summary.append(", ").append(mParseResult.ignoredLineCount()).append(" lines ignored");
        }

        if(mParseResult.duplicateCount() > 0)
        {
            summary.append(", ").append(mParseResult.duplicateCount()).append(" duplicates");
        }

        getSummaryLabel().setText(summary.toString());
        getImportButton().setDisable(mParseResult.talkgroups().isEmpty() ||
            getAliasListComboBox().getSelectionModel().getSelectedItem() == null);
    }

    private void importAliases()
    {
        String aliasListName = getAliasListComboBox().getSelectionModel().getSelectedItem();
        AliasListDefinition definition =
            mConfigurationManager.getAliasModel().getAliasListDefinition(aliasListName);

        if(definition == null || mParseResult.talkgroups().isEmpty())
        {
            return;
        }

        Protocol protocol = protocolFor(definition);
        TalkgroupFormat talkgroupFormat = TalkgroupFormat.get(protocol);
        Set<Integer> existing = getSkipExistingCheckBox().isSelected() ?
            existingTalkgroups(aliasListName) : Set.of();
        List<String> streams = getStreamsMenuButton().getItems().stream()
            .filter(item -> item instanceof CheckMenuItem checkItem && checkItem.isSelected() && !item.isDisable())
            .map(item -> item.getText())
            .toList();
        String group = getGroupField().getText() != null ? getGroupField().getText().trim() : "";
        Integer color = getColorCheckBox().isSelected() ? ColorUtil.toInteger(getColorPicker().getValue()) : null;
        boolean monitor = getMonitorToggle().isSelected();
        Integer priority = getPriorityComboBox().getSelectionModel().getSelectedItem();
        boolean record = getRecordToggle().isSelected();

        List<Alias> created = new ArrayList<>();
        int skippedExisting = 0;
        int outOfRange = 0;

        for(AliasTextImportParser.ParsedTalkgroup parsed: mParseResult.talkgroups())
        {
            if(parsed.talkgroup() < talkgroupFormat.getMinimumValidValue() ||
                parsed.talkgroup() > talkgroupFormat.getMaximumValidValue())
            {
                outOfRange++;
                continue;
            }

            if(existing.contains(parsed.talkgroup()))
            {
                skippedExisting++;
                continue;
            }

            Alias alias = new Alias(parsed.alias());
            alias.setDescription(parsed.description());
            alias.setAliasListDefinition(definition);
            alias.setMatchIdentifier(new Talkgroup(protocol, parsed.talkgroup()));

            if(!group.isEmpty())
            {
                alias.setGroup(group);
            }

            if(color != null)
            {
                alias.setColor(color);
            }

            if(!monitor)
            {
                alias.setCallPriority(Priority.DO_NOT_MONITOR);
            }
            else if(priority != null)
            {
                alias.setCallPriority(priority);
            }

            alias.setRecordable(record);

            for(String stream: streams)
            {
                alias.addBroadcastChannel(stream);
            }

            created.add(alias);
        }

        mConfigurationManager.getAliasModel().addAliases(created);

        StringBuilder message = new StringBuilder();
        message.append("Imported ").append(created.size()).append(" alias").append(created.size() == 1 ? "" : "es")
            .append(" into [").append(aliasListName).append("].");

        if(skippedExisting > 0)
        {
            message.append("\nSkipped ").append(skippedExisting).append(" talkgroups that already have an alias.");
        }

        if(outOfRange > 0)
        {
            message.append("\nSkipped ").append(outOfRange).append(" talkgroups outside the valid ")
                .append(protocol).append(" range.");
        }

        if(mParseResult.ignoredLineCount() > 0)
        {
            message.append("\nIgnored ").append(mParseResult.ignoredLineCount()).append(" unparseable lines.");
        }

        if(mParseResult.duplicateCount() > 0)
        {
            message.append("\nIgnored ").append(mParseResult.duplicateCount()).append(" duplicate talkgroups.");
        }

        Alert alert = new Alert(Alert.AlertType.INFORMATION, message.toString(), ButtonType.OK);
        alert.setTitle("Import Aliases From Text");
        alert.setHeaderText("Alias import complete");
        alert.initOwner(this);
        alert.showAndWait();
        close();
    }

    private Set<Integer> existingTalkgroups(String aliasListName)
    {
        Set<Integer> existing = new HashSet<>();

        for(Alias alias: mConfigurationManager.getAliasModel().aliasList())
        {
            if(alias.matchesAliasList(aliasListName) && alias.getMatchIdentifier() instanceof Talkgroup talkgroup)
            {
                existing.add(talkgroup.getValue());
            }
        }

        return existing;
    }

    private static Protocol protocolFor(AliasListDefinition definition)
    {
        return switch(definition.getFamily())
        {
            case P25 -> Protocol.APCO25;
            case DMR -> Protocol.DMR;
            case NXDN -> Protocol.NXDN;
            case NBFM -> Protocol.NBFM;
        };
    }
}
