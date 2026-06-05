from pathlib import Path
from flask import Flask, redirect, url_for
from db.database import init_app

BASE_DIR = Path(__file__).parent


def create_app(db_path=None, upload_folder=None):
    app = Flask(__name__)
    app.config['SECRET_KEY'] = 'weekly-report-2026'
    app.config['DB_PATH'] = str(db_path or BASE_DIR / 'weekly.db')
    app.config['UPLOAD_FOLDER'] = str(upload_folder or BASE_DIR / 'uploads')
    app.config['MAX_CONTENT_LENGTH'] = 32 * 1024 * 1024

    init_app(app)

    from routes.register import bp as register_bp
    from routes.weekly_input import bp as weekly_bp
    from routes.preview_diff import bp as diff_bp
    from routes.query import bp as query_bp
    from routes.export_routes import bp as export_bp
    from routes.leader import bp as leader_bp

    app.register_blueprint(register_bp)
    app.register_blueprint(weekly_bp)
    app.register_blueprint(diff_bp)
    app.register_blueprint(query_bp)
    app.register_blueprint(export_bp)
    app.register_blueprint(leader_bp)

    @app.route('/')
    def index():
        return redirect(url_for('weekly.weekly_form'))

    return app


app = create_app()

if __name__ == '__main__':
    app.run(debug=True, host='0.0.0.0', port=5000)
