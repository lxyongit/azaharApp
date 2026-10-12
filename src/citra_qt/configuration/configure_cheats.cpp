// Copyright Citra Emulator Project / Lime3DS Emulator Project
// Licensed under GPLv2 or any later version
// Refer to the misc/licenses/gplv2.txt file included.

#include <QCheckBox>
#include <QElapsedTimer>
#include <QHash>
#include <QJsonArray>
#include <QJsonDocument>
#include <QJsonObject>
#include <QJsonParseError>
#include <QMessageBox>
#include <QNetworkAccessManager>
#include <QNetworkReply>
#include <QNetworkRequest>
#include <QSignalBlocker>
#include <QSet>
#include <QSslError>
#include <QTableWidgetItem>
#include <QUrl>
#include <QtGlobal>
#include "configure_cheats.h"
#include "common/logging/log.h"
#include "core/cheats/cheat_base.h"
#include "core/cheats/cheats.h"
#include "core/cheats/gateway_cheat.h"
#include "ui_configure_cheats.h"

namespace {

constexpr auto cheat_server_url = "https://mpgame.lxyong.com/api/app/get-3ds-cheat";
constexpr auto server_origin = "azaharplus:origin=local-server";

QString CheatKey(const QString& name, const QString& code) {
    return name.trimmed() + QChar::Null + code.trimmed();
}

bool IsServerCheat(const QString& notes) {
    return notes.split(QLatin1Char{'\n'}).contains(QString::fromLatin1(server_origin));
}

} // namespace

ConfigureCheats::ConfigureCheats(Cheats::CheatEngine& cheat_engine_, u64 title_id_,
                                 const QString& rom_path, bool cheats_editable_, QWidget* parent)
    : QWidget(parent), ui(std::make_unique<Ui::ConfigureCheats>()), cheat_engine{cheat_engine_},
      cheats_editable{cheats_editable_}, title_id{title_id_} {
    // Setup gui control settings
    ui->setupUi(this);
    ui->tableCheats->setColumnWidth(0, 30);
    ui->tableCheats->setColumnWidth(2, 85);
    ui->tableCheats->horizontalHeader()->setSectionResizeMode(0, QHeaderView::Fixed);
    ui->tableCheats->horizontalHeader()->setSectionResizeMode(1, QHeaderView::Stretch);
    ui->tableCheats->horizontalHeader()->setSectionResizeMode(2, QHeaderView::Fixed);
    ui->lineName->setEnabled(false);
    ui->textCode->setEnabled(false);
    ui->textNotes->setEnabled(false);
    ui->buttonAddCheat->setEnabled(cheats_editable);

    connect(ui->buttonAddCheat, &QPushButton::clicked, this, &ConfigureCheats::OnAddCheat);
    connect(ui->tableCheats, &QTableWidget::cellClicked, this, &ConfigureCheats::OnRowSelected);
    connect(ui->lineName, &QLineEdit::textEdited, this, &ConfigureCheats::OnTextEdited);
    connect(ui->textNotes, &QPlainTextEdit::textChanged, this, &ConfigureCheats::OnTextEdited);
    connect(ui->textCode, &QPlainTextEdit::textChanged, this, &ConfigureCheats::OnTextEdited);

    connect(ui->buttonSave, &QPushButton::clicked, this,
            [this] { SaveCheat(ui->tableCheats->currentRow()); });
    connect(ui->buttonDelete, &QPushButton::clicked, this, &ConfigureCheats::OnDeleteCheat);

    LOG_INFO(Frontend,
             "[CheatServer] Initializing cheats page: title_id={:016X}, rom_path='{}', editable={}",
             title_id, rom_path.toStdString(), cheats_editable);
    cheat_engine.LoadCheatFile(title_id);
    LoadCheats();
    LOG_INFO(Frontend, "[CheatServer] Loaded {} local cheats for title {:016X}", cheats.size(),
             title_id);
    FetchServerCheats(rom_path);
}

ConfigureCheats::~ConfigureCheats() {
    if (cheat_server_manager) {
        for (const auto* reply : cheat_server_manager->findChildren<QNetworkReply*>()) {
            if (!reply->isFinished()) {
                LOG_WARNING(Frontend,
                            "[CheatServer] Closing page with request still pending: "
                            "title_id={:016X}, url='{}'",
                            title_id, reply->url().toString().toStdString());
            }
        }
    }
}

void ConfigureCheats::LoadCheats() {
    cheats = cheat_engine.GetCheats();
    const int cheats_count = static_cast<int>(cheats.size());

    ui->tableCheats->setRowCount(cheats_count);

    for (int i = 0; i < cheats_count; i++) {
        QCheckBox* enabled = new QCheckBox();
        enabled->setChecked(cheats[i]->IsEnabled());
        enabled->setStyleSheet(QStringLiteral("margin-left:7px;"));
        ui->tableCheats->setItem(i, 0, new QTableWidgetItem());
        ui->tableCheats->setCellWidget(i, 0, enabled);
        ui->tableCheats->setItem(
            i, 1, new QTableWidgetItem(QString::fromStdString(cheats[i]->GetName())));
        ui->tableCheats->setItem(
            i, 2, new QTableWidgetItem(QString::fromStdString(cheats[i]->GetType())));
        enabled->setProperty("row", static_cast<int>(i));

#if QT_VERSION < QT_VERSION_CHECK(6, 7, 0)
        connect(enabled, &QCheckBox::stateChanged, this, &ConfigureCheats::OnCheckChanged);
#else
        connect(enabled, &QCheckBox::checkStateChanged, this, &ConfigureCheats::OnCheckChanged);
#endif
    }
}

void ConfigureCheats::FetchServerCheats(const QString& rom_path) {
    const QString path = rom_path.startsWith(QLatin1Char{'!'}) ? rom_path.sliced(1) : rom_path;
    if (title_id == 0 || path.isEmpty()) {
        LOG_WARNING(Frontend,
                    "[CheatServer] Skipping request: title_id={:016X}, missing_game_id={}, "
                    "missing_rom_path={}, rom_path='{}'",
                    title_id, title_id == 0, path.isEmpty(), rom_path.toStdString());
        return;
    }

    ui->labelAvailableCheats->setText(tr("正在获取金手指..."));
    cheat_server_manager = std::make_unique<QNetworkAccessManager>(this);

    QNetworkRequest request{QUrl{QString::fromLatin1(cheat_server_url)}};
    request.setHeader(QNetworkRequest::ContentTypeHeader,
                      QStringLiteral("application/json; charset=utf-8"));
    request.setRawHeader(QByteArrayLiteral("Accept"), QByteArrayLiteral("application/json"));

    QJsonObject payload{
        {QStringLiteral("gameId"),
         QString::number(title_id, 16).rightJustified(16, QLatin1Char{'0'}).toUpper()},
        {QStringLiteral("romPath"), path},
    };
    const QByteArray body = QJsonDocument{payload}.toJson(QJsonDocument::Compact);
    LOG_INFO(Frontend, "[CheatServer] Sending POST: url='{}', payload={}", cheat_server_url,
             body.toStdString());
    QElapsedTimer timer;
    timer.start();
    QNetworkReply* reply = cheat_server_manager->post(request, body);
    LOG_INFO(Frontend, "[CheatServer] POST queued for title {:016X}", title_id);
    connect(reply, &QNetworkReply::sslErrors, this, [this](const QList<QSslError>& errors) {
        for (const auto& error : errors) {
            LOG_WARNING(Frontend, "[CheatServer] TLS error for title {:016X}: {}", title_id,
                        error.errorString().toStdString());
        }
    });
    connect(reply, &QNetworkReply::finished, this, [this, reply, timer] {
        reply->deleteLater();
        const QByteArray response_body = reply->readAll();
        const int http_status = reply->attribute(QNetworkRequest::HttpStatusCodeAttribute).toInt();
        LOG_INFO(Frontend,
                 "[CheatServer] Request finished: title_id={:016X}, url='{}', http_status={}, "
                 "network_error={}, elapsed_ms={}, response_bytes={}",
                 title_id, reply->url().toString().toStdString(), http_status,
                 static_cast<int>(reply->error()), timer.elapsed(), response_body.size());
        if (reply->error() != QNetworkReply::NoError) {
            LOG_WARNING(Frontend,
                        "[CheatServer] Request failed for title {:016X}: {}, response_preview={}",
                        title_id, reply->errorString().toStdString(),
                        response_body.left(1024).toStdString());
            ui->labelAvailableCheats->setText(tr("获取金手指失败"));
            return;
        }

        QJsonParseError parse_error;
        const QJsonDocument document = QJsonDocument::fromJson(response_body, &parse_error);
        if (parse_error.error != QJsonParseError::NoError || !document.isObject()) {
            LOG_WARNING(Frontend,
                        "[CheatServer] Invalid JSON response for title {:016X}: error='{}', "
                        "offset={}, is_object={}, response_preview={}",
                        title_id, parse_error.errorString().toStdString(), parse_error.offset,
                        document.isObject(), response_body.left(1024).toStdString());
            ui->labelAvailableCheats->setText(tr("获取金手指失败"));
            return;
        }
        const QJsonObject response = document.object();
        if (!response.value(QStringLiteral("success")).toBool()) {
            LOG_WARNING(Frontend,
                        "[CheatServer] Server rejected request for title {:016X}: msg='{}', "
                        "response_preview={}",
                        title_id, response.value(QStringLiteral("msg")).toString().toStdString(),
                        response_body.left(1024).toStdString());
            ui->labelAvailableCheats->setText(tr("获取金手指失败"));
            return;
        }

        QList<FetchedCheat> fetched_cheats;
        const QJsonArray groups = response.value(QStringLiteral("data")).toArray();
        int option_count = 0;
        int skipped_count = 0;
        LOG_INFO(Frontend, "[CheatServer] Response accepted: title_id={:016X}, data_is_array={}",
                 title_id, response.value(QStringLiteral("data")).isArray());
        for (const QJsonValue& group_value : groups) {
            const QJsonObject group = group_value.toObject();
            const QString title = group.value(QStringLiteral("title")).toString().trimmed();
            for (const QJsonValue& option_value :
                 group.value(QStringLiteral("options")).toArray()) {
                ++option_count;
                const QJsonObject option = option_value.toObject();
                const QJsonValue value = option.value(QStringLiteral("value"));
                QString code;
                if (value.isString()) {
                    code = value.toString();
                } else if (value.isArray()) {
                    QStringList lines;
                    bool valid = true;
                    for (const QJsonValue& line : value.toArray()) {
                        if (!line.isString()) {
                            valid = false;
                            break;
                        }
                        lines.append(line.toString());
                    }
                    if (valid) {
                        code = lines.join(QLatin1Char{'\n'});
                    }
                }

                const QString description =
                    option.value(QStringLiteral("description")).toString().trimmed();
                QString name = title;
                if (!description.isEmpty()) {
                    name = name.isEmpty() ? description
                                          : name + QStringLiteral(" - ") + description;
                }
                if (!name.isEmpty() && !code.trimmed().isEmpty()) {
                    fetched_cheats.append({name, code});
                } else {
                    ++skipped_count;
                }
            }
        }

        LOG_INFO(Frontend,
                 "[CheatServer] Parsed response: title_id={:016X}, groups={}, options={}, "
                 "cheats={}, skipped_empty_or_invalid={}",
                 title_id, groups.size(), option_count, fetched_cheats.size(), skipped_count);
        ImportServerCheats(fetched_cheats);
        ui->labelAvailableCheats->setText(tr("可用金手指："));
    });
}

void ConfigureCheats::ImportServerCheats(const QList<FetchedCheat>& fetched_cheats) {
    QSet<QString> fetched_keys;
    for (const FetchedCheat& fetched : fetched_cheats) {
        fetched_keys.insert(CheatKey(fetched.name, fetched.code));
    }

    QHash<QString, bool> enabled_states;
    const auto current_cheats = cheat_engine.GetCheats();
    const auto previous_count = current_cheats.size();
    std::size_t removed_count = 0;
    for (const auto& cheat : current_cheats) {
        const QString name = QString::fromStdString(cheat->GetName());
        const QString code = QString::fromStdString(cheat->GetCode());
        const QString notes = QString::fromStdString(cheat->GetComments());
        const QString key = CheatKey(name, code);
        if (IsServerCheat(notes) || fetched_keys.contains(key)) {
            enabled_states.insert(key, cheat->IsEnabled());
        }
    }

    for (std::size_t index = current_cheats.size(); index > 0; --index) {
        const auto& cheat = current_cheats[index - 1];
        const QString key = CheatKey(QString::fromStdString(cheat->GetName()),
                                     QString::fromStdString(cheat->GetCode()));
        if (IsServerCheat(QString::fromStdString(cheat->GetComments())) ||
            fetched_keys.contains(key)) {
            cheat_engine.RemoveCheat(index - 1);
            ++removed_count;
        }
    }

    for (const FetchedCheat& fetched : fetched_cheats) {
        auto cheat = std::make_shared<Cheats::GatewayCheat>(
            fetched.name.toStdString(), fetched.code.toStdString(), std::string{server_origin});
        cheat->SetEnabled(enabled_states.value(CheatKey(fetched.name, fetched.code), false));
        cheat_engine.AddCheat(std::move(cheat));
    }
    if (cheats_editable) {
        cheat_engine.SaveCheatFile(title_id);
    }
    LoadCheats();
    LOG_INFO(Frontend,
             "[CheatServer] Import complete: title_id={:016X}, previous={}, removed={}, "
             "imported={}, total={}, save_requested={}",
             title_id, previous_count, removed_count, fetched_cheats.size(), cheats.size(),
             cheats_editable);
}

bool ConfigureCheats::CheckSaveCheat() {
    auto answer = QMessageBox::warning(
        this, tr("Cheats"), tr("Would you like to save the current cheat?"),
        QMessageBox::Yes | QMessageBox::No | QMessageBox::Cancel, QMessageBox::Cancel);

    if (answer == QMessageBox::Yes) {
        return SaveCheat(last_row);
    } else {
        return answer != QMessageBox::Cancel;
    }
}

bool ConfigureCheats::SaveCheat(int row) {
    if (!cheats_editable) {
        return false;
    }
    if (ui->lineName->text().isEmpty()) {
        QMessageBox::critical(this, tr("Save Cheat"), tr("Please enter a cheat name."));
        return false;
    }
    if (ui->textCode->toPlainText().isEmpty()) {
        QMessageBox::critical(this, tr("Save Cheat"), tr("Please enter the cheat code."));
        return false;
    }

    // Check if the cheat lines are valid
    auto code_lines = ui->textCode->toPlainText().split(QLatin1Char{'\n'}, Qt::SkipEmptyParts);
    for (int i = 0; i < code_lines.size(); ++i) {
        Cheats::GatewayCheat::CheatLine cheat_line(code_lines[i].toStdString());
        if (cheat_line.valid)
            continue;

        auto answer = QMessageBox::warning(
            this, tr("Save Cheat"),
            tr("Cheat code line %1 is not valid.\nWould you like to ignore the error and continue?")
                .arg(i + 1),
            QMessageBox::Yes | QMessageBox::No, QMessageBox::No);
        if (answer == QMessageBox::No)
            return false;
    }

    auto cheat = std::make_shared<Cheats::GatewayCheat>(ui->lineName->text().toStdString(),
                                                        ui->textCode->toPlainText().toStdString(),
                                                        ui->textNotes->toPlainText().toStdString());

    if (newly_created) {
        cheat_engine.AddCheat(std::move(cheat));
        newly_created = false;
    } else {
        cheat_engine.UpdateCheat(row, std::move(cheat));
    }
    cheat_engine.SaveCheatFile(title_id);

    int previous_row = ui->tableCheats->currentRow();
    int previous_col = ui->tableCheats->currentColumn();
    LoadCheats();
    ui->tableCheats->setCurrentCell(previous_row, previous_col);

    edited = false;
    ui->buttonSave->setEnabled(false);
    ui->buttonAddCheat->setEnabled(cheats_editable);
    return true;
}

void ConfigureCheats::OnRowSelected(int row, int column) {
    if (row == last_row) {
        return;
    }
    if (edited && !CheckSaveCheat()) {
        ui->tableCheats->setCurrentCell(last_row, last_col);
        return;
    }
    if (static_cast<std::size_t>(row) < cheats.size()) {
        if (newly_created) {
            // Remove the newly created dummy item
            newly_created = false;
            ui->tableCheats->setRowCount(ui->tableCheats->rowCount() - 1);
        }

        const auto& current_cheat = cheats[row];
        ui->lineName->setText(QString::fromStdString(current_cheat->GetName()));
        ui->textNotes->setPlainText(QString::fromStdString(current_cheat->GetComments()));
        ui->textCode->setPlainText(QString::fromStdString(current_cheat->GetCode()));
    }

    edited = false;
    ui->buttonSave->setEnabled(false);
    ui->buttonDelete->setEnabled(cheats_editable);
    ui->buttonAddCheat->setEnabled(cheats_editable);
    ui->lineName->setEnabled(cheats_editable);
    ui->textCode->setEnabled(cheats_editable);
    ui->textNotes->setEnabled(cheats_editable);

    last_row = row;
    last_col = column;
}

void ConfigureCheats::OnCheckChanged(int state) {
    QCheckBox* checkbox = qobject_cast<QCheckBox*>(sender());
    if (!cheats_editable) {
        if (state != Qt::Unchecked) {
            const QSignalBlocker blocker{checkbox};
            checkbox->setChecked(false);
            QMessageBox::information(this, tr("金手指"), tr("金手指功能需要开通vip"));
        }
        return;
    }
    int row = static_cast<int>(checkbox->property("row").toInt());
    cheats[row]->SetEnabled(state);
    cheat_engine.SaveCheatFile(title_id);
}

void ConfigureCheats::OnTextEdited() {
    if (!cheats_editable) {
        return;
    }
    edited = true;
    ui->buttonSave->setEnabled(true);
}

void ConfigureCheats::OnDeleteCheat() {
    if (!cheats_editable) {
        return;
    }
    if (newly_created) {
        newly_created = false;
    } else {
        cheat_engine.RemoveCheat(ui->tableCheats->currentRow());
        cheat_engine.SaveCheatFile(title_id);
    }

    LoadCheats();
    if (cheats.empty()) {
        ui->lineName->clear();
        ui->textCode->clear();
        ui->textNotes->clear();
        ui->lineName->setEnabled(false);
        ui->textCode->setEnabled(false);
        ui->textNotes->setEnabled(false);
        ui->buttonDelete->setEnabled(false);
        last_row = last_col = -1;
    } else {
        if (last_row >= ui->tableCheats->rowCount()) {
            last_row = ui->tableCheats->rowCount() - 1;
        }
        ui->tableCheats->setCurrentCell(last_row, last_col);

        const auto& current_cheat = cheats[last_row];
        ui->lineName->setText(QString::fromStdString(current_cheat->GetName()));
        ui->textNotes->setPlainText(QString::fromStdString(current_cheat->GetComments()));
        ui->textCode->setPlainText(QString::fromStdString(current_cheat->GetCode()));
    }

    edited = false;
    ui->buttonSave->setEnabled(false);
    ui->buttonAddCheat->setEnabled(cheats_editable);
}

bool ConfigureCheats::ApplyConfiguration() {
    if (cheats_editable && edited) {
        return SaveCheat(ui->tableCheats->currentRow());
    }
    return true;
}

void ConfigureCheats::OnAddCheat() {
    if (!cheats_editable) {
        return;
    }
    if (edited && !CheckSaveCheat()) {
        return;
    }

    int row = ui->tableCheats->rowCount();
    ui->tableCheats->setRowCount(row + 1);
    ui->tableCheats->setCurrentCell(row, 1);

    // create a dummy item
    ui->tableCheats->setItem(row, 1, new QTableWidgetItem(tr("[new cheat]")));
    ui->tableCheats->setItem(row, 2, new QTableWidgetItem(QString{}));
    ui->lineName->clear();
    ui->lineName->setPlaceholderText(tr("[new cheat]"));
    ui->textCode->clear();
    ui->textNotes->clear();
    ui->lineName->setEnabled(true);
    ui->textCode->setEnabled(true);
    ui->textNotes->setEnabled(true);
    ui->buttonSave->setEnabled(true);
    ui->buttonDelete->setEnabled(true);
    ui->buttonAddCheat->setEnabled(false);

    edited = false;
    newly_created = true;
    last_row = row;
    last_col = 1;
}
